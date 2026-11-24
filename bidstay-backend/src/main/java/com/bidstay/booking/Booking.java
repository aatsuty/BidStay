package com.bidstay.booking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Getter @Setter @NoArgsConstructor
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long bidRequestId;
    private Long bidOfferId;
    private UUID directRequestId;
    private Long guestId;
    private Long hotelId;
    private Long roomTypeId;
    private LocalDate checkIn;
    private LocalDate checkOut;
    private int rooms;
    private Integer guestCount;
    private BigDecimal pricePerNight;
    private BigDecimal hourlyRate;
    private Instant arrivalAt;
    private Instant departureAt;
    private Instant blockedUntil;
    private BigDecimal totalAmount;
    private BigDecimal commissionPct;
    private BigDecimal commissionAmount;
    @Enumerated(EnumType.STRING)
    private BookingStatus status;
    private Instant holdExpiresAt;
    private Instant freeCancelUntil;
    private Instant cancelledAt;
    private String paymentRef;
    private Instant createdAt;
    private String hotelNameAtBooking;
    private String hotelAddressAtBooking;
    private String roomNameAtBooking;
    private LocalTime checkInFrom;
    private LocalTime checkOutBy;
    private String guestInstructions;
    @Version
    private Long version;
}
