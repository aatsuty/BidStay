package com.bidstay.booking;

import com.bidstay.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
class RazorpayClient {
    private final RestClient client;
    private final boolean configured;

    RazorpayClient(RestClient.Builder builder,
                   @Value("${bidstay.razorpay.key-id:}") String keyId,
                   @Value("${bidstay.razorpay.key-secret:}") String keySecret) {
        configured = !keyId.isBlank() && !keySecret.isBlank();
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(4));
        requestFactory.setReadTimeout(Duration.ofSeconds(6));
        client = builder.baseUrl("https://api.razorpay.com/v1")
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBasicAuth(keyId, keySecret)).build();
    }

    boolean configured() { return configured; }

    Link checkout(String reference, long amount, Instant expiresAt) {
        try {
            // Recover the link if a previous response was lost after Razorpay created it.
            JsonNode existing = client.get().uri(builder -> builder.path("/payment_links")
                    .queryParam("reference_id", reference).build()).retrieve().body(JsonNode.class);
            if (existing == null || !existing.path("payment_links").isArray()) throw unavailable();
            for (JsonNode link : existing.path("payment_links")) {
                if (reference.equals(link.path("reference_id").asText())) return parseLink(link, amount);
            }

            JsonNode created = client.post().uri("/payment_links")
                    .body(Map.of("amount", amount, "currency", "INR", "reference_id", reference,
                            "accept_partial", false, "expire_by", expiresAt.getEpochSecond(), "description", "BidStay hotel booking",
                            "notify", Map.of("sms", false, "email", false), "reminder_enable", false))
                    .retrieve().body(JsonNode.class);
            return parseLink(created, amount);
        } catch (RestClientException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    Refund refund(String paymentId, String idempotencyKey, long amount) {
        try {
            JsonNode response = client.post().uri("/payments/{paymentId}/refund", paymentId)
                    .header("X-Refund-Idempotency", idempotencyKey)
                    .body(Map.of("amount", amount, "speed", "normal", "receipt", idempotencyKey))
                    .retrieve().body(JsonNode.class);
            if (response == null || response.path("id").asText().isBlank()) throw unavailable();
            return new Refund(response.path("id").asText(), response.path("status").asText());
        } catch (RestClientException failure) {
            throw unavailable();
        }
    }

    private Link parseLink(JsonNode link, long amount) {
        if (link == null || link.path("id").asText().isBlank() || link.path("amount").asLong() != amount
                || !"INR".equals(link.path("currency").asText())) throw unavailable();

        URI url = URI.create(link.path("short_url").asText());
        String host = url.getHost();
        if (!"https".equals(url.getScheme()) || host == null
                || !("rzp.io".equals(host) || "razorpay.com".equals(host) || host.endsWith(".razorpay.com"))) {
            throw unavailable();
        }
        return new Link(link.path("id").asText(), url.toString());
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Online payment is unavailable. Try again shortly.");
    }

    record Link(String id, String url) {}
    record Refund(String id, String status) {}
}
