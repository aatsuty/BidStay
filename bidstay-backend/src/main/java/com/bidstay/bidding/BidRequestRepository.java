package com.bidstay.bidding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface BidRequestRepository extends JpaRepository<BidRequest, Long> {
    List<BidRequest> findByGuestIdOrderByCreatedAtDesc(Long guestId);

    List<BidRequest> findByStatusAndExpiresAtBefore(BidStatus status, Instant cutoff);
}
