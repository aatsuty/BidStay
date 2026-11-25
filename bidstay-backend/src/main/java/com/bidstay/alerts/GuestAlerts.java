package com.bidstay.alerts;

import com.bidstay.common.ApiException;
import com.bidstay.common.BidStayProperties;
import com.bidstay.common.Money;
import com.bidstay.hotel.RoomCategory;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class GuestAlerts {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final BidStayProperties properties;

    private static final RowMapper<Saved> SAVED = (rs, row) -> new Saved(rs.getLong("id"), rs.getString("city"),
            rs.getString("area"), rs.getObject("check_in", LocalDate.class), rs.getObject("check_out", LocalDate.class),
            RoomCategory.valueOf(rs.getString("category")), rs.getInt("rooms"), rs.getInt("guests"),
            rs.getBigDecimal("budget"), rs.getBoolean("enabled"));

    @Transactional
    Saved save(long guestId, SaveSearch cmd) {
        long nights = ChronoUnit.DAYS.between(cmd.checkIn(), cmd.checkOut());
        LocalDate today = LocalDate.now(clock);
        if (cmd.checkIn().isBefore(today) || nights < 1 || nights > properties.maxNights()) {
            throw ApiException.invalid("Choose future check-in and check-out dates");
        }
        jdbc.queryForObject("select id from users where id = ? for update", Long.class, guestId);
        String city = cmd.city().strip().toLowerCase(Locale.ROOT);
        String area = cmd.area() == null ? "" : cmd.area().strip().toLowerCase(Locale.ROOT);
        List<Saved> same = jdbc.query("""
                select * from saved_stay_searches
                 where guest_id = ? and city = ? and area = ? and check_in = ? and check_out = ?
                   and category = ? and rooms = ? and guests = ? and budget = ?
                """, SAVED, guestId, city, area, cmd.checkIn(), cmd.checkOut(), cmd.category().name(), cmd.rooms(), cmd.guests(), cmd.budget());
        if (!same.isEmpty()) {
            return jdbc.queryForObject("""
                    update saved_stay_searches set enabled = true, next_check_at = now() where id = ? returning *
                    """, SAVED, same.getFirst().id());
        }
        Integer count = jdbc.queryForObject("select count(*) from saved_stay_searches where guest_id = ? and check_in >= ?",
                Integer.class, guestId, today);
        if (count != null && count >= 20) throw ApiException.conflict("Remove a saved search before adding another");
        return jdbc.queryForObject("""
                insert into saved_stay_searches(guest_id,city,area,check_in,check_out,category,rooms,guests,budget)
                values (?,?,?,?,?,?,?,?,?) returning *
                """, SAVED, guestId, city, area, cmd.checkIn(), cmd.checkOut(), cmd.category().name(), cmd.rooms(), cmd.guests(), cmd.budget());
    }

    List<Saved> searches(long guestId) {
        return jdbc.query("select * from saved_stay_searches where guest_id = ? order by id desc limit 50", SAVED, guestId);
    }

    Saved setEnabled(long guestId, long searchId, boolean enabled) {
        return jdbc.query("""
                update saved_stay_searches set enabled = ?, next_check_at = now()
                  where id = ? and guest_id = ? returning *
                """, SAVED, enabled, searchId, guestId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Saved search"));
    }

    void delete(long guestId, long searchId) {
        if (jdbc.update("delete from saved_stay_searches where id = ? and guest_id = ?", searchId, guestId) == 0) {
            throw ApiException.notFound("Saved search");
        }
    }

    AlertPage inbox(long guestId, long before) {
        var rows = jdbc.query("""
                select id, kind, title, body, bid_id, booking_id, search_id, created_at, read_at
                from guest_alerts where guest_id = ? and id < ? order by id desc limit 31
                """, (rs, row) -> new Alert(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, Long.class), rs.getObject(6, Long.class), rs.getObject(7, Long.class),
                rs.getTimestamp(8).toInstant(), rs.getTimestamp(9) != null), guestId, before);
        return rows.size() > 30 ? new AlertPage(rows.subList(0, 30), rows.get(29).id()) : new AlertPage(rows, null);
    }

    long unread(long guestId) {
        Long count = jdbc.queryForObject("select count(*) from guest_alerts where guest_id = ? and read_at is null", Long.class, guestId);
        return count == null ? 0 : count;
    }

    void markRead(long guestId, Long alertId) {
        if (alertId != null) {
            jdbc.update("update guest_alerts set read_at = now() where guest_id = ? and id = ? and read_at is null", guestId, alertId);
        } else {
            jdbc.update("update guest_alerts set read_at = now() where guest_id = ? and read_at is null", guestId);
        }
    }

    public void offer(long guestId, long bidId, long offerId, int round, String hotel, BigDecimal amount) {
        notify(guestId, "offer-" + offerId + "-" + round, "OFFER", "You have a hotel offer",
                hotel + " offered " + Money.inr(amount) + " per room per night.", bidId, null);
    }

    public void booking(long guestId, long bookingId, String status) {
        boolean cancelled = "CANCELLED".equals(status);
        notify(guestId, "booking-" + bookingId + "-" + status, cancelled ? "CANCELLATION" : "BOOKING",
                cancelled ? "Booking cancelled" : "Booking confirmed",
                "Check your trip for booking #" + bookingId + ".", null, bookingId);
    }

    private void notify(long user, String key, String kind, String title, String body, Long bid, Long booking) {
        jdbc.update("""
                insert into guest_alerts(guest_id,event_key,kind,title,body,bid_id,booking_id)
                values (?,?,?,?,?,?,?) on conflict (guest_id,event_key) do nothing
                """, user, key, kind, title, body, bid, booking);
    }

    /** Pilot matcher. Each statement claims one bounded due batch across API replicas. */
    @Scheduled(fixedDelayString = "PT15S")
    public void checkDue() {
        wakeAffectedSearches();
        jdbc.update("""
                with due as materialized (
                    select s.* from saved_stay_searches s where s.enabled and s.check_in >= ?
                      and s.next_check_at <= now() order by s.next_check_at, s.id
                    limit 40 for update skip locked
                ), matches as (
                    select s.id as search_id, s.guest_id, s.budget, h.id as hotel_id, h.name from due s
                    cross join lateral (
                        select h.id,h.name from hotels h join room_types r on r.hotel_id = h.id
                        where h.status = 'ACTIVE' and h.city_id in (select destination_cities(s.city))
                           and (s.area = '' or lower(btrim(h.area)) = s.area)
                           and r.category = s.category
                           and r.listed_price <= s.budget
                           and r.max_guests * s.rooms >= s.guests
                           and (select count(*) from room_inventory i
                                where i.room_type_id = r.id and i.stay_date >= s.check_in and i.stay_date < s.check_out
                                  and i.total_rooms - i.held_rooms - i.sold_rooms >= s.rooms) = (s.check_out - s.check_in)
                           and room_has_nightly_capacity(r.id, s.check_in, s.check_out, s.rooms)
                           and not exists(select 1 from guest_alerts a where a.guest_id = s.guest_id
                                 and a.event_key = ('search-' || s.id || '-' || h.id))
                        order by h.id limit 5
                    ) h
                ), alerts as (
                    insert into guest_alerts(guest_id,event_key,kind,title,body,search_id)
                    select guest_id, 'search-' || search_id || '-' || hotel_id, 'PRICE_MATCH', 'A hotel matches your budget',
                      name || ' now has a listed rate within your ₹' || budget || ' budget per room per night.', search_id
                    from matches on conflict (guest_id,event_key) do nothing
                )
                update saved_stay_searches set next_check_at = now() + interval '5 minutes'
                  where id in (select id from due)
                """, LocalDate.now(clock));
    }

    private void wakeAffectedSearches() {
        jdbc.update("""
                with jobs as materialized (
                    select * from saved_search_rechecks order by id limit 8 for update skip locked
                ), candidates as materialized (
                    select j.id as job_id, found.id as search_id from jobs j
                    join room_types r on r.id = j.room_type_id join hotels h on h.id = r.hotel_id
                    cross join lateral (
                        select s.id from saved_stay_searches s
                        where s.enabled and s.id > j.after_search_id and s.check_in >= ?
                          and s.city in (select city_destination_keys(h.city_id)) and s.category = r.category
                          and (s.area = '' or s.area = lower(btrim(h.area)))
                          and s.check_in < j.until_date and s.check_out + 1 > j.from_date
                          and r.listed_price <= s.budget and r.max_guests * s.rooms >= s.guests
                          and not exists(select 1 from guest_alerts g where g.guest_id = s.guest_id
                              and g.event_key = ('search-' || s.id || '-' || h.id))
                        order by s.id limit 100
                    ) found where h.status = 'ACTIVE'
                ), wake as (
                    update saved_stay_searches
                    set next_check_at = least(next_check_at, now())
                    where id in (select search_id from candidates) and next_check_at > now()
                ), remaining as (
                    update saved_search_rechecks j set after_search_id = grouped.last_id
                    from (select job_id, max(search_id) as last_id from candidates group by job_id having count(*) = 100) grouped
                    where j.id = grouped.job_id returning j.id
                )
                delete from saved_search_rechecks where id in (select id from jobs) and id not in (select id from remaining)
                """, LocalDate.now(clock));
    }

    public record SaveSearch(@NotBlank @Size(max=80) String city, @Size(max=100) String area,
                             @NotNull LocalDate checkIn, @NotNull LocalDate checkOut,
                             @NotNull RoomCategory category, @Min(1) @Max(10) int rooms, @Min(1) @Max(40) int guests,
                             @NotNull @Digits(integer=10,fraction=2) @DecimalMin("1") BigDecimal budget) {}
    public record Saved(long id, String city, String area, LocalDate checkIn, LocalDate checkOut,
                        RoomCategory category, int rooms, int guests, BigDecimal budget, boolean enabled) {}
    public record Alert(long id, String kind, String title, String body, Long bidId, Long bookingId, Long searchId,
                        Instant createdAt, boolean read) {}
    public record AlertPage(List<Alert> alerts, Long nextCursor) {}
}
