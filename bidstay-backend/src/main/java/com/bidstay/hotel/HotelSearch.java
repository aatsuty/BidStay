package com.bidstay.hotel;

import com.bidstay.common.ApiException;
import com.bidstay.common.BidStayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
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
                         Double latitude, Double longitude, boolean verified, String hotelPhoto, String roomPhoto,
                         long roomTypeId) {}
    public record Page(List<Result> hotels, Long nextCursor, List<String> areas) {}
    public record Filters(String area, Integer minimumStars, BigDecimal maximumRate, Double latitude, Double longitude) {
        void validate() {
            if (area.length() > 100 || minimumStars != null && (minimumStars < 1 || minimumStars > 5)
                    || maximumRate != null && (maximumRate.signum() <= 0 || maximumRate.compareTo(BigDecimal.valueOf(100_000)) > 0)) {
                throw ApiException.invalid("Check the neighbourhood, star rating and nightly rate filters");
            }
            if (latitude == null && longitude == null) return;
            if (latitude == null || longitude == null || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw ApiException.invalid("Choose a valid location");
            }
        }
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Details details(long hotelId) {
        var header = jdbc.query("""
                select h.name, h.city, h.area, h.star_rating, p.check_in_from, p.check_out_by, p.guest_instructions
                from hotels h left join hotel_stay_policies p on p.hotel_id = h.id
                where h.id = ? and h.status = 'ACTIVE'
                """, (rs, row) -> {
            var checkIn = rs.getObject(5, LocalTime.class);
            StayPolicy policy = checkIn == null ? null : new StayPolicy(checkIn, rs.getObject(6, LocalTime.class), rs.getString(7));
            return new Header(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, Integer.class), policy);
        }, hotelId)
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
                photos.stream().filter(photo -> photo.roomTypeId() == null).toList(), roomTypes, header.stayPolicy());
    }

    public record Details(long id, String name, String city, String area, Integer starRating,
                          List<Photo> photos, List<CatalogRoom> rooms, StayPolicy stayPolicy) {}
    public record Photo(long id, Long roomTypeId, String caption) {}
    public record CatalogRoom(long id, String name, String category, int maxGuests, BigDecimal listedPrice, List<Photo> photos) {}
    private record Header(String name, String city, String area, Integer stars, StayPolicy stayPolicy) {}

    public Page available(String city, LocalDate checkIn, LocalDate checkOut,
                          RoomCategory category, int rooms, int guests, long after, Filters filters) {
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (city.isBlank() || city.length() > 80 || after < 0) throw ApiException.invalid("Invalid destination");
        if (checkIn.isBefore(LocalDate.now(clock)) || nights < 1 || nights > properties.maxNights()) {
            throw ApiException.invalid("Choose a stay between 1 and " + properties.maxNights() + " nights");
        }
        if (rooms < 1 || rooms > 10 || guests < 1 || guests > 40) {
            throw ApiException.invalid("Choose up to 10 rooms and 40 guests");
        }
        filters.validate();

        List<Result> results = jdbc.query("""
                with filters as (select nullif(lower(?), '') as area, ?::integer as minimum_stars,
                                     ?::numeric as maximum_rate, round(?::numeric, 2)::double precision as lat,
                                     round(?::numeric, 2)::double precision as lng)
                select h.id, h.name, h.area, h.star_rating, rt.listed_price, rt.id as room_type_id,
                       round(h.latitude::numeric, 2)::double precision as latitude,
                       round(h.longitude::numeric, 2)::double precision as longitude,
                       (select count(*) from hotel_documents d where d.hotel_id = h.id and d.verified) = 4 as verified,
                       (select m.id from hotel_media m where m.hotel_id = h.id and m.status = 'APPROVED'
                            and m.room_type_id is null order by m.id limit 1) as hotel_photo,
                       (select m.id from hotel_media m where m.hotel_id = h.id and m.status = 'APPROVED'
                            and m.room_type_id = rt.id order by m.id limit 1) as room_photo
                from hotels h join room_types rt on rt.hotel_id = h.id cross join filters f
                where lower(h.city) = lower(?) and h.status = 'ACTIVE' and h.id > ?
                  and rt.category = ? and rt.max_guests * ? >= ?
                  and (f.area is null or lower(h.area) = f.area)
                  and (f.minimum_stars is null or h.star_rating >= f.minimum_stars)
                  and (f.maximum_rate is null or rt.listed_price <= f.maximum_rate)
                  and (f.lat is null or (h.latitude is not null and h.longitude is not null
                       and 6371 * acos(least(1, greatest(-1,
                           sin(radians(f.lat)) * sin(radians(h.latitude))
                           + cos(radians(f.lat)) * cos(radians(h.latitude)) * cos(radians(h.longitude - f.lng))
                       ))) <= 5))
                  and (select count(*) from room_inventory i
                       where i.room_type_id = rt.id and i.stay_date >= ? and i.stay_date < ?
                         and i.total_rooms - i.held_rooms - i.sold_rooms >= ?) = ?
                  and room_has_nightly_capacity(rt.id, ?, ?, ?)
                order by h.id
                limit ?
                """, (rs, row) -> new Result(rs.getLong("id"), rs.getString("name"), rs.getString("area"),
                rs.getObject("star_rating", Integer.class), rs.getBigDecimal("listed_price"),
                rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class), rs.getBoolean("verified"), ListingMedia.thumbnail(rs.getObject("hotel_photo", Long.class)),
                ListingMedia.thumbnail(rs.getObject("room_photo", Long.class)), rs.getLong("room_type_id")),
                filters.area().strip(), filters.minimumStars(), filters.maximumRate(), filters.latitude(), filters.longitude(),
                city.strip(), after, category.name(), rooms, guests, checkIn, checkOut, rooms, nights,
                checkIn, checkOut, rooms, PAGE_SIZE + 1);
        List<String> areas = after == 0 ? jdbc.queryForList("""
                select distinct area from hotels where lower(city) = lower(?) and status = 'ACTIVE' order by area limit 100
                """, String.class, city.strip()) : List.of();
        if (results.size() <= PAGE_SIZE) return new Page(results, null, areas);
        return new Page(results.subList(0, PAGE_SIZE), results.get(PAGE_SIZE - 1).id(), areas);
    }
}
