package com.bidstay.hotel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

    List<RoomType> findByHotelIdOrderById(Long hotelId);

    Optional<RoomType> findByHotelIdAndCategory(Long hotelId, RoomCategory category);

    /** Guest-facing hotel list. Note: min_price is deliberately NOT selected. */
    @Query("""
            select new com.bidstay.hotel.HotelSummary(h.id, h.name, h.area, h.starRating, rt.category, rt.listedPrice)
            from RoomType rt, Hotel h
            where rt.hotelId = h.id
              and lower(h.city) = lower(:city)
              and h.status = :status
              and rt.category = :category
            order by h.name
            """)
    List<HotelSummary> findSummaries(@Param("city") String city,
                                     @Param("status") HotelStatus status,
                                     @Param("category") RoomCategory category);
}
