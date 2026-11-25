package com.bidstay.booking;

import com.bidstay.audit.AuditTrail;
import com.bidstay.audit.AuditTrail.Action;
import com.bidstay.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Objects;

@Service
@RequiredArgsConstructor
class RazorpayPayments {
    private final RazorpayClient gateway;
    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AuditTrail audit;

    @Value("${bidstay.razorpay.webhook-secret:}")
    private String webhookSecret;
    @Value("${bidstay.razorpay.reference-prefix:bidstay}")
    private String referencePrefix;

    boolean enabled() { return gateway.configured() && !webhookSecret.isBlank() && referencePrefix.matches("[A-Za-z0-9-]{1,19}"); }

    @Transactional
    CheckoutLink checkout(Long guestId, Long bookingId) {
        if (!enabled()) throw RazorpayClient.unavailable();
        Booking booking = bookings.lockById(bookingId).filter(b -> b.getGuestId().equals(guestId))
                .orElseThrow(() -> ApiException.notFound("Booking"));
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT || !booking.getHoldExpiresAt().isAfter(clock.instant())) {
            throw ApiException.conflict("This room hold has ended");
        }
        var existing = jdbc.query("select checkout_url from payment_links where booking_id = ?",
                (rs, row) -> new CheckoutLink(rs.getString(1)), bookingId);
        if (!existing.isEmpty()) return existing.getFirst();

        long amount = booking.getTotalAmount().movePointRight(2).longValueExact();
        var link = gateway.checkout(referencePrefix + "-" + bookingId, amount, booking.getHoldExpiresAt());
        jdbc.update("insert into payment_links (booking_id, provider_id, checkout_url) values (?, ?, ?)",
                bookingId, link.id(), link.url());
        return new CheckoutLink(link.url());
    }

    @Transactional
    void capture(JsonNode event) {
        JsonNode payment = event.path("payment").path("entity");
        JsonNode link = event.path("payment_link").path("entity");
        if (!"captured".equals(payment.path("status").asText()) || !"INR".equals(payment.path("currency").asText())) {
            throw ApiException.invalid("Unsupported payment event");
        }
        String paymentId = payment.path("id").asText();
        long paid = payment.path("amount").asLong();
        if (paymentId.isBlank() || paid < 1) throw ApiException.invalid("Payment details are missing");

        Long bookingId = jdbc.query("select booking_id from payment_links where provider_id = ?",
                (rs, row) -> rs.getLong(1), link.path("id").asText()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Payment link"));
        Booking booking = bookings.lockById(bookingId).orElseThrow();
        long expected = booking.getTotalAmount().movePointRight(2).longValueExact();
        if (paid != expected || !(referencePrefix + "-" + bookingId).equals(link.path("reference_id").asText())) {
            throw ApiException.invalid("The payment does not match this booking");
        }

        boolean apply = (booking.getStatus() == BookingStatus.PENDING_PAYMENT && booking.getHoldExpiresAt().isAfter(clock.instant()))
                || (booking.getStatus() == BookingStatus.CONFIRMED && Objects.equals(paymentId, booking.getPaymentRef()));
        int recorded = jdbc.update("""
                insert into payment_captures (provider_payment_id, booking_id, amount_paise, status)
                values (?, ?, ?, ?) on conflict (provider_payment_id) do nothing
                """, paymentId, bookingId, paid, apply ? "APPLIED" : "REFUND_PENDING");
        if (recorded != 0) {
            if (apply) bookingService.confirmPayment(bookingId, paymentId);
            else audit.record(null, Action.REFUND_QUEUED, bookingId, booking.getHotelId());
        }
    }

    @Transactional
    void refundEvent(JsonNode refund, String event) {
        String status = "refund.processed".equals(event) ? "REFUND_PROCESSED" : "REFUND_REVIEW";
        var changed = jdbc.query("""
                update payment_captures p set status = ?, refund_id = ? from bookings b
                where p.booking_id = b.id and p.provider_payment_id = ?
                  and p.status in ('REFUND_PENDING','REFUND_SUBMITTED','REFUND_REVIEW') and p.status <> ?
                  and (p.refund_id is null or p.refund_id = ?) and p.amount_paise = ?
                returning p.booking_id, b.hotel_id
                """, (rs, row) -> new RefundBooking(rs.getLong(1), rs.getLong(2)), status,
                refund.path("id").asText(), refund.path("payment_id").asText(), status,
                refund.path("id").asText(), refund.path("amount").asLong());
        changed.forEach(booking -> recordRefund(status, booking));
    }

    @Scheduled(fixedDelayString = "PT15S")
    void refundLateCaptures() {
        if (!enabled()) return;
        transactions.executeWithoutResult(transaction -> {
            var pending = jdbc.query("""
                    select p.provider_payment_id, p.amount_paise, p.attempts, p.booking_id, b.hotel_id
                    from payment_captures p join bookings b on b.id = p.booking_id
                    where p.status = 'REFUND_PENDING' and p.retry_at <= now()
                    order by p.retry_at limit 1 for update of p skip locked
                    """, (rs, row) -> new PendingRefund(rs.getString(1), rs.getLong(2), rs.getInt(3),
                    new RefundBooking(rs.getLong(4), rs.getLong(5))));
            if (pending.isEmpty()) return;
            PendingRefund capture = pending.getFirst();
            try {
                var refund = gateway.refund(capture.paymentId(), capture.paymentId() + "-late", capture.amount());
                String status = switch (refund.status()) {
                    case "processed" -> "REFUND_PROCESSED";
                    case "failed" -> "REFUND_REVIEW";
                    default -> "REFUND_SUBMITTED";
                };
                jdbc.update("update payment_captures set status = ?, refund_id = ? where provider_payment_id = ?",
                        status, refund.id(), capture.paymentId());
                recordRefund(status, capture.booking());
            } catch (ApiException failure) {
                int attempts = capture.attempts() + 1;
                jdbc.update("""
                        update payment_captures set attempts = ?, status = ?,
                          retry_at = now() + make_interval(secs => ?) where provider_payment_id = ?
                        """, attempts, attempts >= 8 ? "REFUND_REVIEW" : "REFUND_PENDING",
                        Math.min(3600, 15 * (1 << attempts)), capture.paymentId());
                if (attempts >= 8) recordRefund("REFUND_REVIEW", capture.booking());
            }
        });
    }

    private void recordRefund(String status, RefundBooking booking) {
        Action action = switch (status) {
            case "REFUND_PROCESSED" -> Action.REFUND_PROCESSED;
            case "REFUND_REVIEW" -> Action.REFUND_NEEDS_REVIEW;
            default -> Action.REFUND_SUBMITTED;
        };
        audit.record(null, action, booking.id(), booking.hotelId());
    }

    record CheckoutLink(String url) {}
    private record RefundBooking(long id, long hotelId) {}
    private record PendingRefund(String paymentId, long amount, int attempts, RefundBooking booking) {}
}
