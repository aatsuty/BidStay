package com.bidstay.booking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "bookings")
@Getter @Setter @NoArgsConstructor
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long bidRequestId;
    private Long bidOfferId;
    private Long guestId;
    private Long hotelId;
    private Long roomTypeId;
    private LocalDate checkIn;
    private LocalDate checkOut;
    private int rooms;
    private BigDecimal pricePerNight;
    private BigDecimal totalAmount;
    private BigDecimal commissionPct;
    private BigDecimal commissionAmount;
    @Enumerated(EnumType.STRING)
    private BookingStatus status;
    private Instant holdExpiresAt;
    private String paymentRef;
    private Instant createdAt;
    @Version
    private Long version;
}
