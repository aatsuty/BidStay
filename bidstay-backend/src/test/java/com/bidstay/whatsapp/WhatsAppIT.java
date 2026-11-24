package com.bidstay.whatsapp;

import com.bidstay.bidding.BidDtos.CreateBid;
import com.bidstay.bidding.BidService;
import com.bidstay.bidding.OfferStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "bidstay.whatsapp.app-secret=whatsapp-it-secret", "bidstay.whatsapp.phone-number-id=101",
        "bidstay.whatsapp.hotel-bid-template=bidstay_request"
})
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("dev")
class WhatsAppIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired BidService bids;
    @Autowired WhatsAppNotifications notifications;
    @Autowired WhatsAppInbound inbound;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockBean WhatsAppClient transport;
    @MockBean BidReplyAssistant ai;

    @Test
    void providerReplyUsesItsQuotedOfferAndAnAiDraftNeedsConfirmation() throws Exception {
        when(transport.enabled()).thenReturn(true);
        when(transport.sendTemplate(eq("919999900003"), eq("bidstay_request"), anyList())).thenReturn("wamid.bid-one");
        notifications.setConsent(3L, 1L, true);
        LocalDate arrival = LocalDate.now().plusDays(11);
        var bid = bids.create(1L, new CreateBid("Delhi", arrival, arrival.plusDays(1), 1, 2,
                RoomCategory.STANDARD, new BigDecimal("1100"), List.of(1L)));
        long offer = bid.offers().getFirst().offerId();
        notifications.deliver();

        String conditional = "Yes if breakfast is included";
        when(ai.suggest(conditional)).thenReturn("COUNTER 1500");
        mvc.perform(post("/api/v1/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON)
                .content(message("wamid.invalid", "919999900003", "YES"))).andExpect(status().isUnauthorized());
        submit(message("wamid.conditional", "919999900003", conditional));
        inbound.process();
        assertEquals(OfferStatus.PENDING, bids.get(1L, bid.bidId()).offers().getFirst().status());
        assertTrue(jdbc.queryForObject("select content from whatsapp_outbox where event_key = 'wamid.conditional'", String.class)
                .contains("Send that exact reply to confirm"));

        submit(message("wamid.wrong-sender", "919999900005", "COUNTER 1600"));
        submit(message("wamid.counter", "919999900003", "COUNTER 1500"));
        submit(message("wamid.counter", "919999900003", "COUNTER 1500"));
        inbound.process();
        assertEquals(OfferStatus.COUNTERED, bids.get(1L, bid.bidId()).offers().getFirst().status());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from partner_messages where bid_offer_id = ? and channel = 'WHATSAPP'
                  and provider_message_id = 'wamid.counter'
                """, Integer.class, offer));
        assertEquals(0, jdbc.queryForObject("select count(*) from whatsapp_inbound where provider_message_id = 'wamid.wrong-sender'", Integer.class));
    }

    private String message(String id, String from, String text) {
        return """
                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"101"},"messages":[
                  {"id":"%s","from":"%s","context":{"id":"wamid.bid-one"},"text":{"body":"%s"}}
                ]}}]}]}
                """.formatted(id, from, text);
    }

    private void submit(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whatsapp-it-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post("/api/v1/webhooks/whatsapp").header("X-Hub-Signature-256", "sha256=" + signature)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
    }
}
