package com.bidstay.bidding;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bid_offers")
@Getter @Setter @NoArgsConstructor
public class BidOffer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long bidRequestId;
    private Long hotelId;
    private Long roomTypeId;
    @Enumerated(EnumType.STRING)
    private OfferStatus status;
    private BigDecimal offeredPrice;
    private int round;
    private boolean autoAccepted;
    private Instant expiresAt;
    private Instant respondedAt;
    private Instant createdAt;
    @Version
    private Long version;
}
