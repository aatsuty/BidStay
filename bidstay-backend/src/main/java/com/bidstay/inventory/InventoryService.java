package com.bidstay.inventory;

import com.bidstay.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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

    /** Cheap, lock-free check used while evaluating bids. Not a guarantee: hold() is the guarantee. */
    @Transactional(readOnly = true)
    public boolean isAvailable(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        List<RoomInventory> rows = repo.findRange(roomTypeId, checkIn, checkOut);
        return rows.size() == nights && rows.stream().allMatch(r -> r.available() >= rooms);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void hold(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int rooms) {
        List<RoomInventory> rows = lock(roomTypeId, checkIn, checkOut);
        if (rows.stream().anyMatch(r -> r.available() < rooms)) {
            throw ApiException.conflict("Rooms are no longer available for these dates");
        }
        rows.forEach(r -> r.setHeldRooms(r.getHeldRooms() + rooms));
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

    /** Creates any missing nights in [from, to) with the given room count. Safe to call repeatedly. */
    @Transactional
    public int ensureRange(Long roomTypeId, LocalDate from, LocalDate to, int totalRooms) {
        var existing = repo.findRange(roomTypeId, from, to).stream().map(RoomInventory::getStayDate).collect(java.util.stream.Collectors.toSet());
        int added = 0;
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            if (existing.contains(d)) continue;
            RoomInventory r = new RoomInventory();
            r.setRoomTypeId(roomTypeId);
            r.setStayDate(d);
            r.setTotalRooms(totalRooms);
            repo.save(r);
            added++;
        }
        return added;
    }

    /** Owner changes how many rooms they sell per night. Never below what is already held or sold. */
    @Transactional
    public void setTotal(Long roomTypeId, LocalDate from, LocalDate to, int totalRooms) {
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
