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
import org.springframework.scheduling.TaskScheduler;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "bidstay.razorpay.webhook-secret=payment-it-secret",
        "bidstay.tickets.public-origin=https://tickets.example.test",
        "bidstay.whatsapp.token=fixture-token", "bidstay.whatsapp.phone-number-id=101",
        "bidstay.whatsapp.app-secret=fixture-secret", "bidstay.whatsapp.guest-ticket-template=bidstay_guest_ticket"
})
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
    @Autowired BookingTicketDelivery delivery;
    @Autowired TicketContacts contacts;
    @MockBean RazorpayClient gateway;
    @MockBean TicketProviders providers;
    @MockBean TaskScheduler scheduler;

    @Test
    void confirmedBookingQueuesEveryAvailableChannelAndTicketRequiresOwnership() throws Exception {
        when(providers.smsEnabled()).thenReturn(true);
        when(providers.emailEnabled()).thenReturn(true);
        String address = "guest@example.test";
        var verification = new java.util.concurrent.atomic.AtomicReference<String>();
        when(providers.email(eq(address), eq("Verify your BidStay ticket email"), anyString(), anyString()))
                .thenAnswer(call -> {
                    String html = call.getArgument(2);
                    var code = java.util.regex.Pattern.compile("<strong>([0-9]{6})</strong>").matcher(html);
                    org.junit.jupiter.api.Assertions.assertTrue(code.find());
                    verification.set(code.group(1));
                    return "email-verification";
                });
        contacts.sendEmailCode(1L, address);
        assertNull(contacts.contact(1L).email());
        assertThrows(com.bidstay.common.ApiException.class, () -> contacts.sendEmailCode(2L, address));
        assertEquals(address, contacts.verifyEmail(1L, verification.get()).email());
        assertThrows(com.bidstay.common.ApiException.class, () -> contacts.verifyEmail(1L, verification.get()));
        jdbc.update("update users set guest_phone_verified = true where id = 1");
        jdbc.update("insert into whatsapp_consents (user_id, hotel_id, enabled) values (1, null, true)");

        long id = checkout(41, "plink_ticket");
        String ticket = "/api/v1/bookings/" + id + "/ticket";
        mvc.perform(get(ticket).header("X-User-Id", 1)).andExpect(status().isConflict());
        String event = event(id, "plink_ticket", "pay_ticket", 300_000);
        submit(event);
        submit(event);
        String url = "https://tickets.example.test/ticket/" + id;
        assertEquals(2, jdbc.queryForObject("select count(*) from booking_ticket_outbox where booking_id = ?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("select count(*) from whatsapp_outbox where ticket_booking_id = ?", Integer.class, id));
        org.junit.jupiter.api.Assertions.assertTrue(jdbc.queryForObject(
                "select content from whatsapp_outbox where ticket_booking_id = ?", String.class, id).contains(url));
        mvc.perform(get(ticket).header("X-User-Id", 2)).andExpect(status().isNotFound());
        mvc.perform(get(ticket).header("X-User-Id", 1)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("Booking ticket")))
                .andExpect(content().string(containsString("Hotel Sunrise Paharganj")))
                .andExpect(content().string(containsString("pay_ticket")));
        mvc.perform(get(ticket + "/delivery").header("X-User-Id", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.channels.length()").value(3));
        mvc.perform(get("/api/v1/partner/hotels/1/bookings").param("bookingId", Long.toString(id)).header("X-User-Id", 5))
                .andExpect(status().isForbidden());

        when(providers.sms(anyString(), anyString())).thenReturn("sms-ticket");
        when(providers.email(eq(address), eq("BidStay ticket #" + id), anyString(), eq("booking-ticket-" + id)))
                .thenReturn("email-ticket");
        delivery.deliver();
        delivery.deliver();
        verify(providers).sms(anyString(), contains(url));
        verify(providers).email(eq(address), eq("BidStay ticket #" + id), contains("pay_ticket"), eq("booking-ticket-" + id));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from booking_ticket_outbox where booking_id = ? and state = 'SUBMITTED'", Integer.class, id));
        contacts.removeEmail(1L);
        jdbc.update("update users set guest_phone_verified = false where id = 1");
    }

    @Test
    void signedCaptureConfirmsOnceAndCancellationQueuesRefund() throws Exception {
        String policy = "/api/v1/partner/hotels/1/stay-policy";
        String original = """
                {"checkInFrom":"14:00","checkOutBy":"11:00","guestInstructions":"Bring a photo ID."}
                """;
        mvc.perform(put(policy).header("X-User-Id", 5).contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isForbidden());
        mvc.perform(put(policy).header("X-User-Id", 3).contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checkInFrom").value("14:00"));
        long id = checkout(16, "plink_pilot");
        mvc.perform(put(policy).header("X-User-Id", 3).contentType(MediaType.APPLICATION_JSON)
                        .content(original.replace("14:00", "15:00").replace("Bring a photo ID.", "Use the lobby entrance.")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/hotels/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stayPolicy.checkInFrom").value("15:00"))
                .andExpect(jsonPath("$.address").doesNotExist()).andExpect(jsonPath("$.rooms[0].minPrice").doesNotExist());
        mvc.perform(get("/api/v1/bookings/{id}", id).header("X-User-Id", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(3000))
                .andExpect(jsonPath("$.pricePerNight").value(1500))
                .andExpect(jsonPath("$.nights").value(2)).andExpect(jsonPath("$.roomName").isString())
                .andExpect(jsonPath("$.stayPolicy.checkInFrom").value("14:00"))
                .andExpect(jsonPath("$.stayPolicy.guestInstructions").value("Bring a photo ID."))
                .andExpect(jsonPath("$.hotelAddress").doesNotExist()).andExpect(jsonPath("$.receipt").doesNotExist());
        String event = event(id, "plink_pilot", "pay_pilot", 300_000);

        mvc.perform(post("/api/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isUnauthorized());
        assertEquals(BookingStatus.PENDING_PAYMENT, bookings.get(1L, id).status());

        submit(event);
        submit(event);
        assertEquals(BookingStatus.CONFIRMED, bookings.get(1L, id).status());
        assertEquals(1, jdbc.queryForObject("select count(*) from payment_captures where booking_id = ?", Integer.class, id));
        mvc.perform(get("/api/v1/bookings/{id}", id).header("X-User-Id", 2))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bookings/{id}", id).header("X-User-Id", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.receipt.reference").value("pay_pilot"))
                .andExpect(jsonPath("$.receipt.demo").value(false))
                .andExpect(jsonPath("$.receipt.recordedAt").isString())
                .andExpect(jsonPath("$.hotelAddress").isString())
                .andExpect(jsonPath("$.stayPolicy.checkInFrom").value("14:00"));

        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("X-User-Id", 2))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bookings/{id}/cancel", id).header("X-User-Id", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundStatus").value("REFUND_PENDING"));
        String refund = """
                {"event":"refund.processed","payload":{"refund":{"entity":{
                    "id":"rfnd_pilot","payment_id":"pay_pilot","amount":300000
                }}}}
                """;
        submit(refund);
        submit(refund);
        mvc.perform(get("/api/v1/bookings/{id}", id).header("X-User-Id", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundStatus").value("REFUND_PROCESSED"))
                .andExpect(jsonPath("$.receipt.reference").value("pay_pilot"))
                .andExpect(jsonPath("$.receipt.refundReference").value("rfnd_pilot"));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from audit_events where action = 'REFUND_PROCESSED' and subject_id = ?
                """, Integer.class, id));
    }

    @Test
    void laterCaptureIsQueuedForRefundWithoutBooking() throws Exception {
        long id = checkout(26, "plink_late");
        jdbc.update("update bookings set hold_expires_at = now() - interval '1 minute' where id = ?", id);
        submit(event(id, "plink_late", "pay_late", 300_000));
        assertEquals("REFUND_PENDING", jdbc.queryForObject(
                "select status from payment_captures where provider_payment_id = 'pay_late'", String.class));
        assertNull(bookings.get(1L, id).receipt());
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
