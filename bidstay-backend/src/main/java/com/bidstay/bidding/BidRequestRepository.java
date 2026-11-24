package com.bidstay.bidding;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BidRequestRepository extends JpaRepository<BidRequest, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BidRequest> findByIdAndGuestId(Long bidId, Long guestId);

    List<BidRequest> findByGuestIdOrderByCreatedAtDesc(Long guestId);

    List<BidRequest> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAscIdAsc(BidStatus status, Instant cutoff);
}
