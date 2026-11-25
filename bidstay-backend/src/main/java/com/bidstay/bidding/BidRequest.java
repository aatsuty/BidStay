package com.bidstay.bidding;

import com.bidstay.hotel.RoomCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "bid_requests")
@Getter @Setter @NoArgsConstructor
public class BidRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long guestId;
    private String city;
    private LocalDate checkIn;
    private LocalDate checkOut;
    private int rooms;
    private int guests;
    @Enumerated(EnumType.STRING)
    private RoomCategory category;
    private BigDecimal pricePerNight;
    private int round;
    @Enumerated(EnumType.STRING)
    private BidStatus status;
    private Instant expiresAt;
    private Instant createdAt;
    @Version
    private Long version;

    public long nights() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }
}
