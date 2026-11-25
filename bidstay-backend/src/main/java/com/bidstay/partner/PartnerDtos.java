package com.bidstay.partner;

import com.bidstay.hotel.DocType;
import com.bidstay.hotel.HotelStatus;
import com.bidstay.hotel.RoomCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PartnerDtos {
    private PartnerDtos() {}

    public record MyHotel(Long id, String name, String area, HotelStatus status, String rejectionReason) {}

    public record RoomInput(@NotNull RoomCategory category, @NotBlank String name, @Min(1) @Max(10) int maxGuests,
                            @NotNull @DecimalMin("1") BigDecimal listedPrice, @NotNull @DecimalMin("1") BigDecimal minPrice,
                            @Min(1) @Max(500) int totalRooms) {}

    public record RegisterHotel(@NotBlank String name, @NotBlank @Size(max = 80) String city,
                                @NotBlank @Size(max = 80) String area, @NotBlank String address,
                                @Min(0) @Max(5) int starRating, String gstNumber, String contactPhone,
                                @NotEmpty @Valid List<RoomInput> rooms, Double latitude, Double longitude) {}

    public record MapPin(Double latitude, Double longitude) {}

    public record DocumentInput(@NotBlank @Size(max = 300) String reference) {}

    public record DocumentView(DocType type, boolean submitted, boolean verified, String reference, Long versionId) {}

    public record RoomRate(Long roomTypeId, RoomCategory category, String name, BigDecimal listedPrice, BigDecimal minPrice, int totalRooms) {}

    public record Rates(boolean autoMode, boolean autoCounter, int autoCounterPct, boolean autoDecline, List<RoomRate> rooms) {}

    public record RoomRateUpdate(@NotNull Long roomTypeId, @NotNull @DecimalMin("1") BigDecimal listedPrice, @NotNull @DecimalMin("1") BigDecimal minPrice) {}

    public record RatesUpdate(boolean autoMode, boolean autoCounter, @Min(1) @Max(30) int autoCounterPct, boolean autoDecline,
                              @NotEmpty @Valid List<RoomRateUpdate> rooms) {}

    public record NightView(LocalDate date, int total, int held, int sold) {}

    public record RoomAvailability(Long roomTypeId, String name, List<NightView> nights) {}

    public record AvailabilityUpdate(@NotNull Long roomTypeId, @NotNull LocalDate from, @NotNull LocalDate to, @Min(0) @Max(500) int totalRooms) {}

    public record ChatMessageView(Long id, String sender, String kind, String body, Long offerId, Instant at, String channel, String whatsappStatus) {}

    public record ChatSend(@NotBlank @Size(max = 200) String text) {}

    public record BookingRow(Long bookingId, String status, String roomType, LocalDate checkIn, LocalDate checkOut, int rooms,
                             BigDecimal pricePerNight, BigDecimal total, BigDecimal commission, BigDecimal payout,
                             String guestName, String guestPhone, Instant arrival, Instant departure, BigDecimal hourlyRate) {}

    public record Dashboard(long pendingReplies, long bidsLast30Days, long acceptedLast30Days, long counteredLast30Days,
                            long declinedOrExpiredLast30Days, Long avgReplySeconds, long confirmedBookingsLast30Days,
                            BigDecimal payoutLast30Days, BigDecimal commissionLast30Days, long upcomingStays) {}
}
