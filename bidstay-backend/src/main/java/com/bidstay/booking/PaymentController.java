package com.bidstay.booking;

import com.bidstay.auth.CallerId;
import com.bidstay.common.ApiException;
import com.bidstay.common.WebhookSignatures;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class PaymentController {
    private final RazorpayPayments payments;
    private final ObjectMapper json;
    @Value("${bidstay.razorpay.webhook-secret:}")
    private String webhookSecret;

    @GetMapping("/payments/options")
    Options options() {
        return new Options(payments.enabled());
    }

    @PostMapping("/bookings/{bookingId}/payment-link")
    RazorpayPayments.CheckoutLink checkout(@CallerId(guestOnly = true) Long guestId, @PathVariable Long bookingId) {
        return payments.checkout(guestId, bookingId);
    }

    @PostMapping("/webhooks/razorpay")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void webhook(@RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                 @RequestBody byte[] body) throws IOException {
        if (!WebhookSignatures.matches(webhookSecret, body, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        var event = json.readTree(body);
        String name = event.path("event").asText();
        var details = event.path("payload");
        switch (name) {
            case "payment_link.paid" -> payments.capture(details);
            case "refund.processed", "refund.failed" -> payments.refundEvent(details.path("refund").path("entity"), name);
            default -> { /* Razorpay can send other subscribed events. */ }
        }
    }

    record Options(boolean razorpay) {}
}
