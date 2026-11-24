package com.bidstay.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
class WhatsAppClient {
    private final RestClient client;
    private final String phoneNumberId;
    private final String language;
    private final boolean enabled;

    WhatsAppClient(RestClient.Builder builder,
                   @Value("${bidstay.whatsapp.token:}") String token,
                   @Value("${bidstay.whatsapp.phone-number-id:}") String phoneNumberId,
                   @Value("${bidstay.whatsapp.app-secret:}") String appSecret,
                   @Value("${bidstay.whatsapp.graph-version:v23.0}") String version,
                   @Value("${bidstay.whatsapp.language:en}") String language) {
        this.phoneNumberId = phoneNumberId;
        this.language = language;
        this.enabled = !token.isBlank() && !phoneNumberId.isBlank() && !appSecret.isBlank();
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(4));
        requestFactory.setReadTimeout(Duration.ofSeconds(6));
        client = builder.baseUrl("https://graph.facebook.com/" + version)
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    boolean enabled() { return enabled; }

    String sendTemplate(String number, String name, List<String> components) {
        var parameters = components.stream().map(value -> Map.of("type", "text", "text", value)).toList();
        var template = Map.of("name", name, "language", Map.of("code", language),
                "components", List.of(Map.of("type", "body", "parameters", parameters)));
        return send(Map.of("messaging_product", "whatsapp", "to", number, "type", "template", "template", template));
    }

    String sendReply(String number, String messageId, String text) {
        return send(Map.of("messaging_product", "whatsapp", "to", number, "type", "text",
                "context", Map.of("message_id", messageId), "text", Map.of("body", text)));
    }

    private String send(Map<String, Object> payload) {
        JsonNode response = client.post().uri("/{phoneId}/messages", phoneNumberId).body(payload).retrieve().body(JsonNode.class);
        if (response == null || response.path("messages").path(0).path("id").asText().isBlank()) {
            throw new IllegalStateException("WhatsApp did not return a message identifier");
        }
        return response.path("messages").path(0).path("id").asText();
    }
}
