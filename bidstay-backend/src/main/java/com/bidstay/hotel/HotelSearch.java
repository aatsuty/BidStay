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
                         Double latitude, Double longitude, boolean verified, String hotelPhoto, String roomPhoto) {}
    public record Page(List<Result> hotels, Long nextCursor) {}

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Details details(long hotelId) {
        var header = jdbc.query("""
                select name, city, area, star_rating from hotels where id = ? and status = 'ACTIVE'
                """, (rs, row) -> new Header(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, Integer.class)), hotelId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Hotel"));
        var photos = jdbc.query("""
                select id, room_type_id, caption from hotel_media
                where hotel_id = ? and status = 'APPROVED' order by id
                """, (rs, row) -> new Photo(rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3)), hotelId);
        var roomTypes = jdbc.query("""
                select id, name, category, max_guests, listed_price from room_types where hotel_id = ? order by id
                """, (rs, row) -> {
            long id = rs.getLong(1);
            return new CatalogRoom(id, rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5),
                    photos.stream().filter(photo -> java.util.Objects.equals(photo.roomTypeId(), id)).toList());
        }, hotelId);
        return new Details(hotelId, header.name(), header.city(), header.area(), header.stars(),
                photos.stream().filter(photo -> photo.roomTypeId() == null).toList(), roomTypes);
    }

    public record Details(long id, String name, String city, String area, Integer starRating, List<Photo> photos, List<CatalogRoom> rooms) {}
    public record Photo(long id, Long roomTypeId, String caption) {}
    public record CatalogRoom(long id, String name, String category, int maxGuests, BigDecimal listedPrice, List<Photo> photos) {}
    private record Header(String name, String city, String area, Integer stars) {}

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
                       (select count(*) from hotel_documents d where d.hotel_id = h.id and d.verified) = 4 as verified,
                       (select m.id from hotel_media m where m.hotel_id = h.id and m.status = 'APPROVED'
                            and m.room_type_id is null order by m.id limit 1) as hotel_photo,
                       (select m.id from hotel_media m where m.hotel_id = h.id and m.status = 'APPROVED'
                            and m.room_type_id = rt.id order by m.id limit 1) as room_photo
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
                rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class), rs.getBoolean("verified"), ListingMedia.thumbnail(rs.getObject("hotel_photo", Long.class)),
                ListingMedia.thumbnail(rs.getObject("room_photo", Long.class))),
                city.strip(), after, category.name(), rooms, guests, checkIn, checkOut, rooms, nights, PAGE_SIZE + 1);
        if (results.size() <= PAGE_SIZE) return new Page(results, null);
        return new Page(results.subList(0, PAGE_SIZE), results.get(PAGE_SIZE - 1).id());
    }
}
