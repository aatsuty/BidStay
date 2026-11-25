package com.bidstay.booking;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Component
class TicketProviders {
    private final RestClient sms;
    private final RestClient email;
    private final String smsService;
    private final String from;
    private final boolean smsConfigured;
    private final boolean emailConfigured;

    TicketProviders(RestClient.Builder builder,
                    @Value("${bidstay.tickets.twilio-account-sid:}") String accountSid,
                    @Value("${bidstay.tickets.twilio-auth-token:}") String authToken,
                    @Value("${bidstay.tickets.twilio-messaging-service-sid:}") String smsService,
                    @Value("${bidstay.tickets.resend-api-key:}") String emailKey,
                    @Value("${bidstay.tickets.email-from:}") String from) {
        this.smsService = smsService;
        this.from = from;
        smsConfigured = accountSid.matches("AC[a-fA-F0-9]{32}") && !authToken.isBlank()
                && smsService.matches("MG[a-fA-F0-9]{32}");
        emailConfigured = !emailKey.isBlank() && !from.isBlank();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(4));
        factory.setReadTimeout(Duration.ofSeconds(6));
        sms = builder.clone().baseUrl("https://api.twilio.com/2010-04-01/Accounts/" + accountSid)
                .requestFactory(factory).defaultHeaders(h -> h.setBasicAuth(accountSid, authToken)).build();
        email = builder.clone().baseUrl("https://api.resend.com").requestFactory(factory)
                .defaultHeaders(h -> h.setBearerAuth(emailKey)).build();
    }

    boolean smsEnabled() { return smsConfigured; }
    boolean emailEnabled() { return emailConfigured; }

    String sms(String number, String content) {
        var data = new LinkedMultiValueMap<String, String>();
        data.add("To", "+91" + number);
        data.add("MessagingServiceSid", smsService);
        data.add("Body", content);
        JsonNode response = sms.post().uri("/Messages.json").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(data).retrieve().body(JsonNode.class);
        return providerId(response, "sid");
    }

    String email(String address, String subject, String html, String idempotencyKey) {
        JsonNode response = email.post().uri("/emails").header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("from", from, "to", address, "subject", subject, "html", html)).retrieve().body(JsonNode.class);
        return providerId(response, "id");
    }

    private String providerId(JsonNode response, String field) {
        String value = response == null ? "" : response.path(field).asText();
        if (value.isBlank() || value.length() > 255) throw new IllegalStateException("Missing message identifier");
        return value;
    }
}
