package com.bidstay.whatsapp;

import com.bidstay.auth.CallerId;
import com.bidstay.common.ApiException;
import com.bidstay.common.WebhookSignatures;
import com.bidstay.user.AccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class WhatsAppController {
    private final WhatsAppNotifications notifications;
    private final WhatsAppInbound inbound;
    private final AccessService access;
    private final ObjectMapper mapper;
    @Value("${bidstay.whatsapp.verify-token:}") private String verifyToken;
    @Value("${bidstay.whatsapp.app-secret:}") private String appSecret;
    @Value("${bidstay.whatsapp.phone-number-id:}") private String phoneId;

    @GetMapping("/whatsapp/guest")
    Consent guest(@CallerId(verifiedOnly = true, guestOnly = true) Long userId) { return consent(userId, null); }

    @PutMapping("/whatsapp/guest")
    Consent setGuest(@CallerId(verifiedOnly = true, guestOnly = true) Long userId, @RequestBody Preference body) {
        return update(userId, null, body.enabled());
    }

    @GetMapping("/partner/hotels/{hotelId}/whatsapp")
    Consent staff(@CallerId(verifiedOnly = true) Long userId, @PathVariable Long hotelId) {
        access.requireHotelStaff(userId);
        access.requireHotelAccess(userId, hotelId);
        return consent(userId, hotelId);
    }

    @PutMapping("/partner/hotels/{hotelId}/whatsapp")
    Consent setStaff(@CallerId(verifiedOnly = true) Long userId, @PathVariable Long hotelId, @RequestBody Preference body) {
        access.requireHotelStaff(userId);
        access.requireHotelAccess(userId, hotelId);
        return update(userId, hotelId, body.enabled());
    }

    @GetMapping(value = "/webhooks/whatsapp", produces = "text/plain")
    String handshake(@RequestParam("hub.mode") String mode, @RequestParam("hub.verify_token") String token,
                     @RequestParam("hub.challenge") String challenge) {
        if (verifyToken.isBlank() || !"subscribe".equals(mode) || !MessageDigest.isEqual(
                verifyToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) throw invalid();
        return challenge;
    }

    @PostMapping("/webhooks/whatsapp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void inbound(@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                 @RequestBody byte[] body) throws IOException {
        if (signature == null || !signature.startsWith("sha256=")
                || !WebhookSignatures.matches(appSecret, body, signature.substring(7))) throw invalid();
        for (var entry : mapper.readTree(body).path("entry")) {
            for (var change : entry.path("changes")) {
                var value = change.path("value");
                if (phoneId.isBlank() || !phoneId.equals(value.path("metadata").path("phone_number_id").asText())) continue;
                value.path("statuses").forEach(inbound::delivery);
                value.path("messages").forEach(inbound::message);
            }
        }
    }

    private Consent update(Long userId, Long hotelId, boolean enabled) {
        if (enabled && !notifications.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WhatsApp messaging is unavailable");
        }
        notifications.setConsent(userId, hotelId, enabled);
        return consent(userId, hotelId);
    }

    private Consent consent(Long userId, Long hotelId) {
        return new Consent(notifications.enabled(), notifications.consent(userId, hotelId));
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid WhatsApp webhook credentials");
    }

    record Preference(boolean enabled) {}
    record Consent(boolean available, boolean enabled) {}
}
