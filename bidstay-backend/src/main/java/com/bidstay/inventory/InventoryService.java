package com.bidstay.inventory;

import com.bidstay.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Owns room-night counts. The hold -> sold lifecycle:
 *   hold()        guest accepted an offer and is paying   (held += rooms)
 *   confirmSale() payment succeeded                       (held -= rooms, sold += rooms)
 *   release()     hold expired or payment failed          (held -= rooms)
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final RoomInventoryRepository repo;
    private final JdbcTemplate jdbc;
    public static final ZoneId STAY_ZONE = ZoneId.of("Asia/Kolkata");
    public static final long TURNOVER_SECONDS = 30 * 60;

    /** Cheap, lock-free check used while evaluating bids. Not a guarantee: hold() is the guarantee. */
    @Transactional(readOnly = true)
    public boolean isAvailable(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        List<RoomInventory> rows = repo.findRange(roomTypeId, checkIn, checkOut);
        return rows.size() == nights && rows.stream().allMatch(r -> r.available() >= rooms)
                && Boolean.TRUE.equals(jdbc.queryForObject("select room_has_nightly_capacity(?, ?, ?, ?)",
                Boolean.class, roomTypeId, checkIn, checkOut, rooms));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void hold(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms, Instant arrival, Instant blockedUntil) {
        lockPool(roomTypeId);
        if (usesSharedInventory(roomTypeId)) {
            requireWindow(roomTypeId, arrival == null ? dayStart(checkIn) : arrival,
                    blockedUntil == null ? dayStart(checkOut.plusDays(1)) : blockedUntil, rooms);
        }
        List<RoomInventory> rows = lock(roomTypeId, checkIn, checkOut);
        if (rows.stream().anyMatch(r -> r.available() < rooms)) {
            throw ApiException.conflict("Rooms are no longer available for these dates");
        }
        rows.forEach(r -> r.setHeldRooms(r.getHeldRooms() + rooms));
    }

    /** Serializes new holds and capacity edits for the same room pool across all replicas. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPool(Long roomTypeId) {
        jdbc.query("select id from room_types where id = ? for update", (rs, row) -> rs.getLong(1), roomTypeId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Room type"));
    }

    public void requireWindow(Long roomTypeId, Instant from, Instant until, int rooms) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select room_has_capacity(?, ?, ?, ?)",
                Boolean.class, roomTypeId, Timestamp.from(from), Timestamp.from(until), rooms))) {
            throw ApiException.conflict("Rooms are no longer available for this time");
        }
    }

    private boolean usesSharedInventory(Long roomTypeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from hourly_stay_rates where room_type_id = ?)",
                Boolean.class, roomTypeId));
    }

    private Instant dayStart(LocalDate date) {
        return date.atStartOfDay(STAY_ZONE).toInstant();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        lock(roomTypeId, checkIn, checkOut).forEach(r -> r.setHeldRooms(Math.max(0, r.getHeldRooms() - rooms)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmSale(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        lock(roomTypeId, checkIn, checkOut).forEach(r -> {
            r.setHeldRooms(r.getHeldRooms() - rooms);
            r.setSoldRooms(r.getSoldRooms() + rooms);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelSale(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        lock(roomTypeId, checkIn, checkOut).forEach(row -> row.setSoldRooms(row.getSoldRooms() - rooms));
    }

    /** Creates any missing nights in [from, to) with the given room count. Safe to call repeatedly. */
    @Transactional
    public int ensureRange(Long roomTypeId, LocalDate from, LocalDate to, int totalRooms) {
        return jdbc.update("""
                insert into room_inventory (room_type_id, stay_date, total_rooms)
                select ?, day::date, ? from generate_series(?::date::timestamp, (?::date - 1)::timestamp, interval '1 day') days(day)
                on conflict (room_type_id, stay_date) do nothing
                """, roomTypeId, totalRooms, from, to);
    }

    /** Owner changes how many rooms they sell per night. Never below what is already held or sold. */
    @Transactional
    public void setTotal(Long roomTypeId, LocalDate from, LocalDate to, int totalRooms) {
        lockPool(roomTypeId);
        if (usesSharedInventory(roomTypeId)) {
            boolean occupied = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists (select 1 from generate_series(?::date::timestamp, (?::date - 1)::timestamp, interval '1 day') days(day)
                    where room_peak_occupancy(?, day at time zone 'Asia/Kolkata', (day + interval '1 day') at time zone 'Asia/Kolkata') > ?)
                    """, Boolean.class, from, to, roomTypeId, totalRooms));
            if (occupied) throw ApiException.conflict("The new capacity is below the rooms held or booked for these dates");
        }
        for (RoomInventory r : repo.lockRange(roomTypeId, from, to)) {
            if (totalRooms < r.getHeldRooms() + r.getSoldRooms()) {
                throw ApiException.invalid(r.getStayDate() + " already has " + (r.getHeldRooms() + r.getSoldRooms())
                        + " room(s) booked or on hold. You cannot go below that.");
            }
            r.setTotalRooms(totalRooms);
        }
        ensureRange(roomTypeId, from, to, totalRooms);
    }

    @Transactional(readOnly = true)
    public List<RoomInventory> view(Long roomTypeId, LocalDate from, LocalDate to) {
        return repo.findRange(roomTypeId, from, to);
    }

    private List<RoomInventory> lock(Long roomTypeId, LocalDate checkIn, LocalDate checkOut) {
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        List<RoomInventory> rows = repo.lockRange(roomTypeId, checkIn, checkOut);
        if (rows.size() != nights) {
            throw ApiException.conflict("Inventory is not published for all requested nights");
        }
        return rows;
    }
}
