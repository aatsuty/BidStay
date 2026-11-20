package com.bidstay.admin;

import com.bidstay.hotel.HotelStatus;
import com.bidstay.partner.PartnerDtos.DocumentView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AdminDtos {
    private AdminDtos() {}

    /** The three success targets from Section 8 of the business plan, measured on live data. */
    public record Target(String key, String label, Double actualPct, int targetPct, String basis) {}

    public record Stats(long liveHotels, long pendingHotels, long totalBids, long confirmedBookings,
                        BigDecimal bookingValue, BigDecimal commissionEarned, List<Target> targets) {}

    public record HotelRow(Long id, String name, String city, String area, HotelStatus status, String rejectionReason,
                           String contactPhone, String gstNumber, int roomTypes, Instant createdAt,
                           boolean allDocsVerified, List<DocumentView> documents) {}

    public record Reject(@NotBlank @Size(max = 300) String reason) {}

    public record BookingRow(Long id, String hotel, String guest, String status, LocalDate checkIn, LocalDate checkOut,
                             int rooms, BigDecimal total, BigDecimal commission, Instant createdAt) {}

    public record BidRow(Long id, String guest, String category, LocalDate checkIn, LocalDate checkOut, int rooms,
                         BigDecimal pricePerNight, int round, String status, long offers, long offersReady, Instant createdAt) {}
}
