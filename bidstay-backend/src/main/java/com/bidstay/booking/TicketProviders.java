package com.bidstay.booking;

import com.bidstay.common.ResendEmail;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
class TicketProviders {
    private final RestClient sms;
    private final ResendEmail email;
    private final String smsService;
    private final String from;
    private final boolean smsConfigured;

    TicketProviders(RestClient.Builder builder, ResendEmail email,
                    @Value("${bidstay.tickets.twilio-account-sid:}") String accountSid,
                    @Value("${bidstay.tickets.twilio-auth-token:}") String authToken,
                    @Value("${bidstay.tickets.twilio-messaging-service-sid:}") String smsService,
                    @Value("${bidstay.tickets.email-from:}") String from) {
        this.smsService = smsService;
        this.from = from;
        smsConfigured = accountSid.matches("AC[a-fA-F0-9]{32}") && !authToken.isBlank()
                && smsService.matches("MG[a-fA-F0-9]{32}");
        this.email = email;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(4));
        factory.setReadTimeout(Duration.ofSeconds(6));
        sms = builder.clone().baseUrl("https://api.twilio.com/2010-04-01/Accounts/" + accountSid)
                .requestFactory(factory).defaultHeaders(h -> h.setBasicAuth(accountSid, authToken)).build();
    }

    boolean smsEnabled() { return smsConfigured; }
    boolean emailEnabled() { return email.enabled() && !from.isBlank(); }

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
        return email.send(from, address, subject, html, idempotencyKey);
    }

    private String providerId(JsonNode response, String field) {
        String value = response == null ? "" : response.path(field).asText();
        if (value.isBlank() || value.length() > 255) throw new IllegalStateException("Missing message identifier");
        return value;
    }
}
