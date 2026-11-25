package com.bidstay.booking;

import com.bidstay.auth.CallerId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService service;
    private final BookingReviews reviews;

    @PostMapping("/{bookingId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@CallerId(guestOnly = true) Long userId, @PathVariable Long bookingId,
                       @Valid @RequestBody BookingReviews.ReviewInput input) {
        reviews.submit(userId, bookingId, input);
    }

    @PostMapping("/request-id")
    public RequestId requestId(@CallerId(guestOnly = true) Long userId) {
        return new RequestId(UUID.randomUUID());
    }

    @PostMapping("/listed")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView listed(@CallerId(guestOnly = true) Long userId, @Valid @RequestBody ListedStay stay) {
        return service.startListed(userId, stay);
    }

    @PostMapping("/hourly")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView hourly(@CallerId(guestOnly = true) Long userId, @Valid @RequestBody HourlyStay stay) {
        return service.startHourly(userId, stay);
    }

    @GetMapping("/direct")
    public List<BookingService.DirectTrip> direct(@CallerId(guestOnly = true) Long userId,
                                                   @RequestParam(defaultValue = "9223372036854775807") long before) {
        return service.directTrips(userId, before);
    }

    @PostMapping("/{bookingId}/cancel")
    public BookingView cancel(@CallerId(guestOnly = true) Long userId, @PathVariable Long bookingId) {
        return service.cancel(userId, bookingId);
    }

    @GetMapping("/{bookingId}")
    public BookingView get(@CallerId(guestOnly = true) Long userId, @PathVariable Long bookingId) {
        return service.get(userId, bookingId);
    }

    public record ListedStay(@NotNull UUID requestId, @NotNull @Positive Long roomTypeId,
                             @NotNull LocalDate checkIn, @NotNull LocalDate checkOut,
                             @Min(1) @Max(10) int rooms, @Min(1) @Max(40) int guests,
                             @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal listedRate) {}

    public record HourlyStay(@NotNull UUID requestId, @NotNull @Positive Long roomTypeId,
                             @NotNull LocalDate date, @NotNull LocalTime arrival, int hours,
                             @Min(1) @Max(10) int rooms, @Min(1) @Max(40) int guests,
                             @NotNull @DecimalMin("0.01") @Digits(integer = 9, fraction = 2) BigDecimal ratePerRoom) {}
    public record RequestId(UUID requestId) {}
}
