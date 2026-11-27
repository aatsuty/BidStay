package com.bidstay.common;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Component
public class ResendEmail {
    private final RestClient client;
    private final boolean enabled;

    public ResendEmail(RestClient.Builder builder, @Value("${bidstay.tickets.resend-api-key:}") String apiKey) {
        enabled = !apiKey.isBlank();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(4));
        factory.setReadTimeout(Duration.ofSeconds(6));
        client = builder.clone().baseUrl("https://api.resend.com").requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(apiKey)).build();
    }

    public boolean enabled() {
        return enabled;
    }

    public String send(String from, String to, String subject, String html, String idempotencyKey) {
        JsonNode response = client.post().uri("/emails").header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("from", from, "to", to, "subject", subject, "html", html))
                .retrieve().body(JsonNode.class);
        String id = response == null ? "" : response.path("id").asText();
        if (id.isBlank() || id.length() > 255) throw new IllegalStateException("Missing message identifier");
        return id;
    }
}
