package com.bidstay.booking;

import com.bidstay.auth.CallerId;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/**
 * DEV ONLY: pretends the payment gateway reported success.
 * Replaced by the Razorpay webhook (with signature verification) in the next step.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/v1/dev/bookings")
@RequiredArgsConstructor
public class DevPaymentController {

    public record PayRequest(@NotBlank String paymentRef) {}

    private final BookingService service;

    @PostMapping("/{bookingId}/confirm-payment")
    public BookingView confirm(@CallerId(guestOnly = true) Long userId, @PathVariable Long bookingId, @jakarta.validation.Valid @RequestBody PayRequest body) {
        service.get(userId, bookingId);
        return service.confirmPayment(bookingId, body.paymentRef());
    }
}
