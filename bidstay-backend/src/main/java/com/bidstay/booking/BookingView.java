package com.bidstay.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** hotelName and hotelAddress are only filled once the booking is CONFIRMED (anti-disintermediation rule). */
public record BookingView(Long bookingId, BookingStatus status, LocalDate checkIn, LocalDate checkOut,
                          int rooms, BigDecimal pricePerNight, BigDecimal totalAmount,
                          Instant payBy, String hotelName, String hotelAddress) {
}
