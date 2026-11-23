package com.bidstay.bidding;

import com.bidstay.hotel.RoomCategory;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class BidDtos {
    private BidDtos() {}

    public record CreateBid(
            @NotBlank String city,
            @NotNull LocalDate checkIn,
            @NotNull LocalDate checkOut,
            @Min(1) @Max(10) int rooms,
            @Min(1) @Max(40) int guests,
            @NotNull RoomCategory category,
            @NotNull @DecimalMin("1.00") BigDecimal pricePerNight,
            @NotEmpty List<Long> hotelIds) {}

    public record Raise(@NotNull @DecimalMin("1.00") BigDecimal pricePerNight) {}

    public record HotelRespond(@NotNull HotelAction action, BigDecimal counterPrice, @NotNull @Min(1) Integer round) {}

    /** What the guest sees. Contains no hotel minimum price and no hotel address. */
    public record OfferView(Long offerId, Long hotelId, String hotelName, String area,
                            OfferStatus status, BigDecimal pricePerNight, BigDecimal totalForStay,
                            boolean autoAccepted, Instant expiresAt) {}

    public record BidView(Long bidId, BidStatus status, int round, int maxRounds,
                          BigDecimal pricePerNight, LocalDate checkIn, LocalDate checkOut,
                          int rooms, Instant expiresAt, Long bookingId, List<OfferView> offers) {}

    /** What the hotel sees. The guest stays anonymous until the booking is paid. */
    public record HotelOfferView(Long offerId, String roomType, BigDecimal bidPricePerNight,
                                 BigDecimal listedPrice, LocalDate checkIn, LocalDate checkOut,
                                 int rooms, long nights, int round, Instant respondBy) {}
}
