package com.bidstay.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByBidRequestIdOrderByIdDesc(Long bidRequestId);

    List<Booking> findByStatusAndHoldExpiresAtBefore(BookingStatus status, Instant cutoff);
}
