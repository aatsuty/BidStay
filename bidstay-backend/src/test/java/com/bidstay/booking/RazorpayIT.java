package com.bidstay.booking;

import com.bidstay.bidding.BidDtos.CreateBid;
import com.bidstay.bidding.BidService;
import com.bidstay.hotel.RoomCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "bidstay.razorpay.webhook-secret=payment-it-secret")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("dev")
class RazorpayIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired BidService bids;
    @Autowired BookingService bookings;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockBean RazorpayClient gateway;

    @Test
    void signedCaptureConfirmsOnceAndCancellationQueuesRefund() throws Exception {
        long id = checkout(16, "plink_pilot");
        String event = event(id, "plink_pilot", "pay_pilot", 300_000);

        mvc.perform(post("/api/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isUnauthorized());
        assertEquals(BookingStatus.PENDING_PAYMENT, bookings.get(1L, id).status());

        submit(event);
        submit(event);
        assertEquals(BookingStatus.CONFIRMED, bookings.get(1L, id).status());
        assertEquals(1, jdbc.queryForObject("select count(*) from payment_captures where booking_id = ?", Integer.class, id));

        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("X-User-Id", 2))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("X-User-Id", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundStatus").value("REFUND_PENDING"));
    }

    @Test
    void laterCaptureIsQueuedForRefundWithoutBooking() throws Exception {
        long id = checkout(26, "plink_late");
        jdbc.update("update bookings set hold_expires_at = now() - interval '1 minute' where id = ?", id);
        submit(event(id, "plink_late", "pay_late", 300_000));
        assertEquals("REFUND_PENDING", jdbc.queryForObject(
                "select status from payment_captures where provider_payment_id = 'pay_late'", String.class));
        assertEquals(null, jdbc.queryForObject("select payment_ref from bookings where id = ?", String.class, id));
    }

    private long checkout(int offset, String link) throws Exception {
        LocalDate checkIn = LocalDate.now().plusDays(offset);
        var bid = bids.create(1L, new CreateBid("Delhi", checkIn, checkIn.plusDays(2), 1, 2,
                RoomCategory.STANDARD, new BigDecimal("1500"), List.of(1L)));
        long id = bids.acceptOffer(1L, bid.bidId(), bid.offers().getFirst().offerId()).bookingId();
        when(gateway.configured()).thenReturn(true);
        when(gateway.checkout(eq("bidstay-" + id), eq(300_000L), any(java.time.Instant.class))).thenReturn(new RazorpayClient.Link(link, "https://rzp.io/i/test"));
        when(gateway.refund(anyString(), anyString(), anyLong()))
                .thenThrow(new com.bidstay.common.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Fake refund failure"));
        mvc.perform(post("/api/v1/bookings/{id}/payment-link", id).header("X-User-Id", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://rzp.io/i/test"));
        return id;
    }

    private String event(long id, String link, String payment, long amount) {
        return """
                {"event":"payment_link.paid","payload":{
                  "payment":{"entity":{"id":"%s","amount":%d,"status":"captured","currency":"INR"}},
                  "payment_link":{"entity":{"id":"%s","reference_id":"bidstay-%d"}}
                }}
                """.formatted(payment, amount, link, id);
    }

    private void submit(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("payment-it-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post("/api/v1/webhooks/razorpay").header("X-Razorpay-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
    }
}
