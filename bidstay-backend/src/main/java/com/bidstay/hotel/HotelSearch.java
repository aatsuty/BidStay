package com.bidstay.hotel;

import com.bidstay.common.ApiException;
import com.bidstay.common.BidStayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HotelSearch {
    private static final int PAGE_SIZE = 24;
    private final JdbcTemplate jdbc;
    private final BidStayProperties properties;
    private final Clock clock;

    public record Result(long id, String name, String area, Integer starRating, BigDecimal listedPrice,
                         Double latitude, Double longitude, boolean verified) {}
    public record Page(List<Result> hotels, Long nextCursor) {}

    public Page available(String city, LocalDate checkIn, LocalDate checkOut,
                          RoomCategory category, int rooms, int guests, long after) {
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (city.isBlank() || city.length() > 80 || after < 0) throw ApiException.invalid("Invalid destination");
        if (checkIn.isBefore(LocalDate.now(clock)) || nights < 1 || nights > properties.maxNights()) {
            throw ApiException.invalid("Choose a stay between 1 and " + properties.maxNights() + " nights");
        }
        if (rooms < 1 || rooms > 10 || guests < 1 || guests > 40) {
            throw ApiException.invalid("Choose up to 10 rooms and 40 guests");
        }

        List<Result> results = jdbc.query("""
                select h.id, h.name, h.area, h.star_rating, rt.listed_price,
                       round(h.latitude::numeric, 2)::double precision as latitude,
                       round(h.longitude::numeric, 2)::double precision as longitude,
                       (select count(*) from hotel_documents d where d.hotel_id = h.id and d.verified) = 4 as verified
                from hotels h join room_types rt on rt.hotel_id = h.id
                where lower(h.city) = lower(?) and h.status = 'ACTIVE' and h.id > ?
                  and rt.category = ? and rt.max_guests * ? >= ?
                  and (select count(*) from room_inventory i
                       where i.room_type_id = rt.id and i.stay_date >= ? and i.stay_date < ?
                         and i.total_rooms - i.held_rooms - i.sold_rooms >= ?) = ?
                order by h.id
                limit ?
                """, (rs, row) -> new Result(rs.getLong("id"), rs.getString("name"), rs.getString("area"),
                rs.getObject("star_rating", Integer.class), rs.getBigDecimal("listed_price"),
                rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class), rs.getBoolean("verified")),
                city.strip(), after, category.name(), rooms, guests, checkIn, checkOut, rooms, nights, PAGE_SIZE + 1);
        if (results.size() <= PAGE_SIZE) return new Page(results, null);
        return new Page(results.subList(0, PAGE_SIZE), results.get(PAGE_SIZE - 1).id());
    }
}
