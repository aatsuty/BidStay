package com.bidstay.bidding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BidOfferRepository extends JpaRepository<BidOffer, Long> {
    List<BidOffer> findByBidRequestId(Long bidRequestId);

    Optional<BidOffer> findByIdAndHotelId(Long id, Long hotelId);

    List<BidOffer> findByHotelIdAndStatusOrderByCreatedAtDesc(Long hotelId, OfferStatus status);

    List<BidOffer> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAscIdAsc(OfferStatus status, Instant cutoff);

    List<BidOffer> findByBidRequestIdAndStatusIn(Long bidRequestId, Collection<OfferStatus> statuses);
}
