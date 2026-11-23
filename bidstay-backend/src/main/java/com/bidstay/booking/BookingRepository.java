package com.bidstay.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByBidRequestIdOrderByIdDesc(Long bidRequestId);

    List<Booking> findTop100ByStatusAndHoldExpiresAtBeforeOrderByHoldExpiresAtAscIdAsc(BookingStatus status, Instant cutoff);
}
