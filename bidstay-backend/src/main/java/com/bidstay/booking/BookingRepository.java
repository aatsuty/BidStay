package com.bidstay.booking;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByBidRequestIdOrderByIdDesc(Long bidRequestId);
    Optional<Booking> findByGuestIdAndDirectRequestId(Long guestId, UUID directRequestId);
    List<Booking> findTop50ByGuestIdAndDirectRequestIdIsNotNullAndIdLessThanOrderByIdDesc(Long guestId, Long before);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Booking> findTop100ByStatusAndHoldExpiresAtBeforeOrderByHoldExpiresAtAscIdAsc(BookingStatus status, Instant cutoff);
}
