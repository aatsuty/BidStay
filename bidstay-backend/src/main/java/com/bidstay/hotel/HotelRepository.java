package com.bidstay.hotel;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HotelRepository extends JpaRepository<Hotel, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hotel h where h.id = :id")
    Optional<Hotel> lockById(@Param("id") Long id);

    @Query("select h from Hotel h where h.id < :before and (:status is null or h.status = :status) order by h.id desc")
    List<Hotel> adminPage(@Param("status") HotelStatus status, @Param("before") Long before, Pageable size);

    Optional<Hotel> findByName(String name);
}
