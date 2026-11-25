package com.bidstay.hotel;

import com.bidstay.audit.AuditTrail;
import com.bidstay.common.ApiException;
import com.bidstay.common.BidStayProperties;
import com.bidstay.inventory.InventoryService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.bidstay.inventory.InventoryService.STAY_ZONE;
import static com.bidstay.inventory.InventoryService.TURNOVER_SECONDS;

@Service
@RequiredArgsConstructor
public class HourlyStays {
    private static final LocalTime OPENS = LocalTime.of(8, 0);
    private static final LocalTime CLOSES = LocalTime.of(22, 0);
    private static final int PAGE_SIZE = 24;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final BidStayProperties properties;
    private final InventoryService inventory;
    private final AuditTrail audit;

    public Window window(LocalDate date, LocalTime arrival, int hours) {
        if (hours != 3 && hours != 6) throw ApiException.invalid("Choose a 3- or 6-hour stay");
        if (arrival.isBefore(OPENS) || arrival.isAfter(CLOSES.minusHours(hours))
                || arrival.getMinute() % 30 != 0 || arrival.getSecond() != 0 || arrival.getNano() != 0) {
            throw ApiException.invalid("Choose a half-hour start between 08:00 and 22:00, with departure by 22:00 IST");
        }
        Instant starts = date.atTime(arrival).atZone(STAY_ZONE).toInstant();
        if (!starts.isAfter(clock.instant().plus(properties.paymentHold()))
                || date.isAfter(LocalDate.now(clock.withZone(STAY_ZONE)).plusDays(30))) {
            throw ApiException.invalid("Choose a future slot within the next 30 days and leave time to pay");
        }
        Instant ends = starts.plus(Duration.ofHours(hours));
        return new Window(starts, ends, ends.plusSeconds(TURNOVER_SECONDS));
    }

    public BigDecimal rate(Long roomTypeId, int hours) {
        return jdbc.query("""
                select case when ? = 3 then three_hours else six_hours end
                from hourly_stay_rates where room_type_id = ? and enabled
                """, (rs, row) -> rs.getBigDecimal(1), hours, roomTypeId).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("This room is no longer offered for hourly stays"));
    }

    @Transactional(readOnly = true)
    public Page search(String city, LocalDate date, LocalTime arrival, int hours, RoomCategory category,
                       int rooms, int guests, long after) {
        return search(city, date, arrival, hours, category, rooms, guests, after, "", MapSearchFilter.NONE);
    }

    @Transactional(readOnly = true)
    public Page search(String city, LocalDate date, LocalTime arrival, int hours, RoomCategory category,
                       int rooms, int guests, long after, String area, MapSearchFilter map) {
        if (city.isBlank() || city.length() > 80 || area.length() > 100 || after < 0
                || rooms < 1 || rooms > 10 || guests < 1 || guests > 40) {
            throw ApiException.invalid("Check the destination, guest count and room count");
        }
        map.validate();
        Window window = window(date, arrival, hours);
        List<Listing> listings = jdbc.query("""
                select h.id, h.name, h.area, r.id, r.name,
                       case when ? = 3 then hr.three_hours else hr.six_hours end,
                       (select m.id from hotel_media m where m.hotel_id = h.id and m.status = 'APPROVED'
                        and (m.room_type_id = r.id or m.room_type_id is null)
                        order by m.room_type_id nulls last, m.id limit 1),
                       c.name, h.map_point[1], h.map_point[0]
                from hotels h join location_cities c on c.id = h.city_id join room_types r on r.hotel_id = h.id
                join hourly_stay_rates hr on hr.room_type_id = r.id and hr.enabled
                where h.status = 'ACTIVE' and h.city_id in (select destination_cities(?)) and h.id > ?
                  and r.category = ? and r.max_guests * ? >= ?
                  and (? = '' or h.neighbourhood_id in
                        (select id from location_neighbourhoods where city_id = h.city_id and name_key = lower(?)))
                  and hotel_in_map(h.map_point, round(?::numeric, 2)::float8, round(?::numeric, 2)::float8, ?, ?, ?, ?)
                  and room_has_capacity(r.id, ?, ?, ?)
                order by h.id limit ?
                """, (rs, row) -> new Listing(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getBigDecimal(6), ListingMedia.thumbnail(rs.getObject(7, Long.class)), rs.getString(8),
                rs.getObject(9, Double.class), rs.getObject(10, Double.class)),
                hours, city.strip(), after, category.name(), rooms, guests, area.strip(), area.strip(),
                map.latitude(), map.longitude(), map.south(), map.west(), map.north(), map.east(), Timestamp.from(window.arrival()),
                Timestamp.from(window.blockedUntil()), rooms, PAGE_SIZE + 1);
        if (listings.size() <= PAGE_SIZE) return new Page(listings, null);
        return new Page(listings.subList(0, PAGE_SIZE), listings.get(PAGE_SIZE - 1).hotelId());
    }

    public List<RoomRates> partnerRates(Long hotelId) {
        return jdbc.query("""
                select r.id, r.name, coalesce(hr.enabled, false), hr.three_hours, hr.six_hours
                from room_types r left join hourly_stay_rates hr on hr.room_type_id = r.id
                where r.hotel_id = ? order by r.id
                """, (rs, row) -> new RoomRates(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5)), hotelId);
    }

    @Transactional
    public void update(Long actor, Long hotelId, Long roomTypeId, RatesChange rates) {
        inventory.lockPool(roomTypeId);
        var room = jdbc.query("""
                select r.id, p.hotel_id is not null as has_policy
                from room_types r left join hotel_stay_policies p on p.hotel_id = r.hotel_id
                where r.id = ? and r.hotel_id = ?
                """, (rs, row) -> rs.getBoolean(2), roomTypeId, hotelId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Room type"));
        if (rates.enabled() && !room) throw ApiException.conflict("Save the hotel's check-in and check-out policy first");
        if (rates.enabled() && (rates.threeHours() == null || rates.sixHours() == null)) {
            throw ApiException.invalid("Enter rates for both stay durations");
        }
        int changed = rates.enabled()
                ? jdbc.update("""
                    insert into hourly_stay_rates (room_type_id, enabled, three_hours, six_hours)
                    values (?, true, ?, ?)
                    on conflict (room_type_id) do update
                    set enabled = true, three_hours = excluded.three_hours, six_hours = excluded.six_hours
                    """, roomTypeId, rates.threeHours(), rates.sixHours())
                : jdbc.update("update hourly_stay_rates set enabled = false where room_type_id = ? and enabled", roomTypeId);
        if (changed != 0) audit.record(actor, AuditTrail.Action.HOURLY_RATES_UPDATED, roomTypeId, hotelId);
    }

    public record Window(Instant arrival, Instant departure, Instant blockedUntil) {}
    public record Listing(long hotelId, String hotelName, String area, long roomTypeId, String roomName,
                          BigDecimal ratePerRoom, String photo, String city, Double latitude, Double longitude) {}
    public record Page(List<Listing> hotels, Long nextCursor) {}
    public record RoomRates(long roomTypeId, String name, boolean enabled, BigDecimal threeHours, BigDecimal sixHours) {}
    public record RatesChange(@NotNull Boolean enabled,
                              @DecimalMin("0.01") @Digits(integer = 9, fraction = 2) BigDecimal threeHours,
                              @DecimalMin("0.01") @Digits(integer = 9, fraction = 2) BigDecimal sixHours) {}
}
