package com.bidstay.booking;

import com.bidstay.hotel.StayPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Property address and receipt are only disclosed to the guest after payment was applied. */
public record BookingView(Long bookingId, BookingStatus status, LocalDate checkIn, LocalDate checkOut,
                          int rooms, BigDecimal pricePerNight, BigDecimal totalAmount,
                          Instant payBy, String hotelName, String hotelAddress, Instant freeCancelUntil, String refundStatus,
                          long nights, String roomName, StayPolicy stayPolicy, PaymentReceipt receipt) {
    public record PaymentReceipt(String reference, Instant recordedAt, String refundReference, boolean demo) {}
}
