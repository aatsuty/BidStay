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

    public List<String> destinations(String prefix, boolean hourly) {
        String term = prefix.strip();
        if (term.length() > 80) throw ApiException.invalid("Destination is too long");
        if (term.length() < 2) return List.of();
        return jdbc.queryForList("""
                select min(h.city)
                from hotels h
                where h.status = 'ACTIVE' and lower(h.city) like lower(?) escape '!'
                  and (not ? or exists (
                      select 1 from room_types r join hourly_stay_rates rates on rates.room_type_id = r.id
                      where r.hotel_id = h.id and rates.enabled
                  ))
                group by lower(h.city)
                order by lower(h.city)
                limit 8
                """, String.class, prefixPattern(term), hourly);
    }

    public List<Place> places(String prefix, boolean hourly) {
        String term = prefix.strip();
        if (term.length() > 182) throw ApiException.invalid("Destination is too long");
        if (term.length() < 2) return List.of();
        String neighbourhood = term.split(",", 2)[0].strip();
        return jdbc.query("""
                with input as (select lower(?) as prefix, lower(?) as area_prefix, lower(?) as name),
                places as (
                    select c.name as label, c.name as city, '' as area, 'CITY' as kind, c.id as city_id, null::bigint as neighbourhood_id
                    from location_cities c, input i where c.name_key like i.prefix escape '!'
                    union
                    select c.name, c.name, '', 'CITY', c.id, null
                    from location_city_aliases a join location_cities c on c.id = a.city_id, input i
                    where a.alias like i.prefix escape '!'
                    union all
                    select r.name, r.name, '', 'REGION', null, null
                    from location_regions r, input i where r.name_key like i.prefix escape '!'
                    union all
                    select n.name || ', ' || c.name, c.name, n.name, 'NEIGHBOURHOOD', c.id, n.id
                    from location_neighbourhoods n join location_cities c on c.id = n.city_id, input i
                    where n.name_key like i.area_prefix escape '!'
                      and (position(',' in i.name) = 0 or c.name_key = btrim(substring(i.name from position(',' in i.name) + 1)))
                )
                select p.label, p.city, p.area, p.kind from places p cross join input i
                where (p.kind <> 'CITY' or not exists (select 1 from location_regions where name_key = lower(p.label)))
                  and exists (
                    select 1 from hotels h where h.status = 'ACTIVE'
                      and (h.city_id = p.city_id or (p.kind = 'REGION' and h.city_id in (select destination_cities(p.city))))
                      and (p.neighbourhood_id is null or h.neighbourhood_id = p.neighbourhood_id)
                      and (not ? or exists (
                          select 1 from room_types rt join hourly_stay_rates rates on rates.room_type_id = rt.id
                          where rt.hotel_id = h.id and rates.enabled))
                )
                order by (lower(p.label) = i.name) desc, p.kind = 'NEIGHBOURHOOD', p.label
                limit 8
                """, (rs, row) -> new Place(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                prefixPattern(term), prefixPattern(neighbourhood), term, hourly);
    }

    private static String prefixPattern(String term) {
        return term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public record Place(String label, String city, String area, String kind) {}

    public record Result(long id, String name, String area, Integer starRating, BigDecimal listedPrice,
                         Double latitude, Double longitude, boolean verified, String hotelPhoto, String roomPhoto,
                         long roomTypeId, String city) {}
    public record Page(List<Result> hotels, Long nextCursor, List<String> areas) {}
    public record Filters(String area, Integer minimumStars, BigDecimal maximumRate, MapSearchFilter map) {
        void validate() {
            if (area.length() > 100 || minimumStars != null && (minimumStars < 1 || minimumStars > 5)
                    || maximumRate != null && (maximumRate.signum() <= 0 || maximumRate.compareTo(BigDecimal.valueOf(100_000)) > 0)) {
                throw ApiException.invalid("Check the neighbourhood, star rating and nightly rate filters");
            }
            map.validate();
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
                select r.id, r.name, r.category, r.max_guests, r.listed_price, hr.three_hours, hr.six_hours
                from room_types r
                left join hourly_stay_rates hr on hr.room_type_id = r.id and hr.enabled
                where r.hotel_id = ? order by r.id
                """, (rs, row) -> {
            long id = rs.getLong(1);
            BigDecimal threeHours = rs.getBigDecimal(6);
            HourlyRates hourlyRates = threeHours == null ? null : new HourlyRates(threeHours, rs.getBigDecimal(7));
            return new CatalogRoom(id, rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5),
                    photos.stream().filter(photo -> java.util.Objects.equals(photo.roomTypeId(), id)).toList(), hourlyRates);
        }, hotelId);
        return new Details(hotelId, header.name(), header.city(), header.area(), header.stars(),
                photos.stream().filter(photo -> photo.roomTypeId() == null).toList(), roomTypes, header.stayPolicy());
    }

    public record Details(long id, String name, String city, String area, Integer starRating,
                          List<Photo> photos, List<CatalogRoom> rooms, StayPolicy stayPolicy) {}
    public record Photo(long id, Long roomTypeId, String caption) {}
    public record CatalogRoom(long id, String name, String category, int maxGuests, BigDecimal listedPrice,
                              List<Photo> photos, HourlyRates hourlyRates) {}
    public record HourlyRates(BigDecimal threeHours, BigDecimal sixHours) {}
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
        MapSearchFilter map = filters.map();

        List<Result> results = jdbc.query("""
                with filters as (select nullif(lower(?), '') as area, ?::integer as minimum_stars,
                                     ?::numeric as maximum_rate, round(?::numeric, 2)::double precision as lat,
                                     round(?::numeric, 2)::double precision as lng)
                select h.id, h.name, h.area, c.name as city, h.star_rating, rt.listed_price, rt.id as room_type_id,
                       h.map_point[1] as latitude, h.map_point[0] as longitude,
                       (select count(*) from hotel_documents d where d.hotel_id = h.id and d.verified) = 4 as verified,
                       (select m.id from hotel_media m where m.hotel_id = h.id and m.status = 'APPROVED'
                            and m.room_type_id is null order by m.id limit 1) as hotel_photo,
                       (select m.id from hotel_media m where m.hotel_id = h.id and m.status = 'APPROVED'
                            and m.room_type_id = rt.id order by m.id limit 1) as room_photo
                from hotels h join location_cities c on c.id = h.city_id
                join room_types rt on rt.hotel_id = h.id cross join filters f
                where h.city_id in (select destination_cities(?)) and h.status = 'ACTIVE' and h.id > ?
                  and rt.category = ? and rt.max_guests * ? >= ?
                  and (f.area is null or h.neighbourhood_id in
                        (select id from location_neighbourhoods where city_id = h.city_id and name_key = f.area))
                  and (f.minimum_stars is null or h.star_rating >= f.minimum_stars)
                  and (f.maximum_rate is null or rt.listed_price <= f.maximum_rate)
                  and hotel_in_map(h.map_point, f.lat, f.lng, ?, ?, ?, ?)
                  and (select count(*) from room_inventory i
                       where i.room_type_id = rt.id and i.stay_date >= ? and i.stay_date < ?
                         and i.total_rooms - i.held_rooms - i.sold_rooms >= ?) = ?
                  and room_has_nightly_capacity(rt.id, ?, ?, ?)
                order by h.id
                limit ?
                """, (rs, row) -> new Result(rs.getLong("id"), rs.getString("name"), rs.getString("area"),
                rs.getObject("star_rating", Integer.class), rs.getBigDecimal("listed_price"),
                rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class), rs.getBoolean("verified"), ListingMedia.thumbnail(rs.getObject("hotel_photo", Long.class)),
                ListingMedia.thumbnail(rs.getObject("room_photo", Long.class)), rs.getLong("room_type_id"), rs.getString("city")),
                filters.area().strip(), filters.minimumStars(), filters.maximumRate(), map.latitude(), map.longitude(),
                city.strip(), after, category.name(), rooms, guests, map.south(), map.west(), map.north(), map.east(),
                checkIn, checkOut, rooms, nights,
                checkIn, checkOut, rooms, PAGE_SIZE + 1);
        List<String> areas = after == 0 ? jdbc.queryForList("""
                select distinct n.name from hotels h join location_neighbourhoods n on n.id = h.neighbourhood_id
                where h.city_id in (select destination_cities(?)) and h.status = 'ACTIVE' order by n.name limit 100
                """, String.class, city.strip()) : List.of();
        if (results.size() <= PAGE_SIZE) return new Page(results, null, areas);
        return new Page(results.subList(0, PAGE_SIZE), results.get(PAGE_SIZE - 1).id(), areas);
    }
}
