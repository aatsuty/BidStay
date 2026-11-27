package com.bidstay.auth;

import com.bidstay.common.ResendEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "bidstay.auth.guest-email-from=BidStay <auth@example.com>",
        "bidstay.auth.guest-email-secret=local-integration-secret-with-at-least-32-bytes"
})
@AutoConfigureMockMvc
@Testcontainers
class GuestEmailAuthIT {
    private static final String GUEST = "/api/v1/auth/guest";
    private static final Pattern CODE = Pattern.compile("<strong>([0-9]{6})</strong>");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockBean ResendEmail email;
    @MockBean PhoneVerifier phone;

    @Test
    void emailCreatesOnlyAGuestAfterProofAndConcurrentReplayCannotCreateAnotherSession() throws Exception {
        String address = "guest@example.com";
        var challenge = sendEmail("/email", "Guest@Example.com", null);

        mvc.perform(get(GUEST + "/methods")).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(true));
        assertEquals(0, guestsWithEmail(address));
        request("/email/code", Map.of("email", address), null).andExpect(status().isTooManyRequests());

        Callable<org.springframework.mock.web.MockHttpServletResponse> attempt = () ->
                request("/email/verify", proof(address, challenge), null).andReturn().getResponse();
        try (var requests = Executors.newFixedThreadPool(2)) {
            var first = requests.submit(attempt);
            var second = requests.submit(attempt);
            var results = java.util.List.of(first.get(), second.get());
            assertEquals(java.util.List.of(200, 401), results.stream().map(r -> r.getStatus()).sorted().toList());
            var success = results.stream().filter(r -> r.getStatus() == 200).findFirst().orElseThrow();
            assertEquals("no-store", success.getHeader("Cache-Control"));
            var session = json.readTree(success.getContentAsString());
            String authorization = "Bearer " + session.get("token").asText();
            mvc.perform(get("/api/v1/me").header("Authorization", authorization))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("GUEST"))
                    .andExpect(jsonPath("$.phone").doesNotExist());
            mvc.perform(get("/api/v1/admin/stats").header("Authorization", authorization))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/partner/hotels").header("Authorization", authorization))
                    .andExpect(status().isForbidden());
            verifyListedBooking(authorization);
        }
        assertEquals(1, guestsWithEmail(address));
    }

    @Test
    void challengeIsRequiredAndLimitsGuessesAndExpiry() throws Exception {
        String address = "attempts@example.com";
        var challenge = sendEmail("/email", address, null);
        request("/email/verify", proof(address, new Challenge(UUID.randomUUID().toString(), challenge.code())), null)
                .andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("select attempts from guest_email_codes where email = ?", Integer.class, address));
        String badCode = challenge.code().equals("000000") ? "000001" : "000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            request("/email/verify", proof(address, new Challenge(challenge.id(), badCode)), null)
                    .andExpect(status().isUnauthorized());
        }
        request("/email/verify", proof(address, challenge), null).andExpect(status().isUnauthorized());
        assertEquals(0, guestsWithEmail(address));

        String expiredAddress = "expired@example.com";
        var expired = sendEmail("/email", expiredAddress, null);
        jdbc.update("update guest_email_codes set expires_at = now() - interval '1 minute' where email = ?", expiredAddress);
        request("/email/verify", proof(expiredAddress, expired), null).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyFreshMobileSessionCanLinkAndTheSameAccountCanSignInWithEitherMethod() throws Exception {
        String address = "linked@example.com";
        JsonNode mobileSession = signInWithSms("9886500001");
        var guestId = mobileSession.get("guest").get("id").asLong();
        String authorization = "Bearer " + mobileSession.get("token").asText();
        request("/email/link/code", Map.of("email", address), null).andExpect(status().isUnauthorized());
        var link = sendEmail("/email/link", address, authorization);

        request("/email/verify", proof(address, link), null).andExpect(status().isUnauthorized());
        String other = "Bearer " + signInWithSms("9886500002").get("token").asText();
        request("/email/link/verify", proof(address, link), other).andExpect(status().isUnauthorized());
        request("/email/link/verify", proof(address, link), authorization).andExpect(status().isNoContent());
        mvc.perform(get(GUEST + "/email/link").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(address));

        jdbc.update("update guest_email_codes set resend_at = now() - interval '1 minute' where email = ?", address);
        var signIn = sendEmail("/email", address, null);
        var signedIn = body(request("/email/verify", proof(address, signIn), null).andExpect(status().isOk()));
        assertEquals(guestId, signedIn.get("guest").get("id").asLong());
        assertEquals("9886500001", signedIn.get("guest").get("phone").asText());
        request("/email/link/code", Map.of("email", "other@example.com"), "Bearer " + signedIn.get("token").asText())
                .andExpect(status().isForbidden());

        jdbc.update("update guest_email_codes set resend_at = now() - interval '1 minute' where email = ?", address);
        var duplicate = sendEmail("/email/link", address, other);
        request("/email/link/verify", proof(address, duplicate), other).andExpect(status().isConflict());
        assertEquals(guestId, jdbc.queryForObject("select id from users where guest_sign_in_email = ?", Long.class, address));

        jdbc.update("update auth_sessions set guest_sms_verified_at = now() - interval '16 minutes' where user_id = ?", guestId);
        request("/email/link/code", Map.of("email", "other@example.com"), authorization).andExpect(status().isForbidden());
    }

    @Test
    void emailDeliveryFailureKeepsCodeInactiveAndReturnsGenericError() throws Exception {
        String address = "unavailable@example.com";
        when(email.enabled()).thenReturn(true);
        when(email.send(anyString(), eq(address), anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("provider data"));
        request("/email/code", Map.of("email", address), null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Email verification is unavailable. Try again later or use mobile sign-in."));
        assertEquals(0, jdbc.queryForObject("select count(*) from guest_email_codes where email = ? and code_digest is not null",
                Integer.class, address));
    }

    private void verifyListedBooking(String authorization) throws Exception {
        long hotel = jdbc.queryForObject("""
                insert into hotels (name, city, area, address, status)
                values ('Email Auth Test Hotel', 'Delhi', 'Delhi', 'Test address', 'ACTIVE') returning id
                """, Long.class);
        long room = jdbc.queryForObject("""
                insert into room_types (hotel_id, category, name, max_guests, listed_price, min_price, total_rooms)
                values (?, 'STANDARD', 'Standard Room', 2, 2000, 1500, 1) returning id
                """, Long.class, hotel);
        LocalDate arrival = LocalDate.now().plusDays(5);
        mvc.perform(get("/api/v1/hotels/{id}", hotel)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/bookings/request-id")).andExpect(status().isUnauthorized());
        var requestId = body(mvc.perform(post("/api/v1/bookings/request-id").header("Authorization", authorization))
                .andExpect(status().isOk())).get("requestId").asText();
        var stay = Map.of("requestId", requestId, "roomTypeId", room, "checkIn", arrival,
                "checkOut", arrival.plusDays(1), "rooms", 1, "guests", 2, "listedRate", 2000);
        var booking = body(mvc.perform(post("/api/v1/bookings/listed").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(stay)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT")));
        long id = booking.get("bookingId").asLong();
        mvc.perform(post("/api/v1/bookings/{id}/payment-link", id).header("Authorization", authorization))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/bookings/{id}", id).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    private int guestsWithEmail(String address) {
        return jdbc.queryForObject("select count(*) from users where guest_sign_in_email = ?", Integer.class, address);
    }

    private Challenge sendEmail(String method, String submittedAddress, String authorization) throws Exception {
        when(email.enabled()).thenReturn(true);
        var response = body(request(method + "/code", Map.of("email", submittedAddress), authorization)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")));
        var html = org.mockito.ArgumentCaptor.forClass(String.class);
        String id = response.get("challengeId").asText();
        verify(email).send(eq("BidStay <auth@example.com>"), eq(submittedAddress.toLowerCase(java.util.Locale.ROOT)),
                anyString(), html.capture(), eq("guest-auth/" + id));
        var matcher = CODE.matcher(html.getValue());
        assertTrue(matcher.find());
        return new Challenge(id, matcher.group(1));
    }

    private JsonNode signInWithSms(String number) throws Exception {
        when(phone.send(number)).thenReturn("VE" + number);
        when(phone.verify("VE" + number, "123456")).thenReturn(true);
        request("/code", Map.of("phone", number), null).andExpect(status().isNoContent());
        return body(request("/verify", Map.of("phone", number, "code", "123456"), null).andExpect(status().isOk()));
    }

    private Map<String, String> proof(String address, Challenge challenge) {
        return Map.of("email", address, "challengeId", challenge.id(), "code", challenge.code());
    }

    private ResultActions request(String path, Map<String, String> body, String authorization) throws Exception {
        var request = post(GUEST + path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (authorization != null) request.header("Authorization", authorization);
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString());
    }

    private record Challenge(String id, String code) {}
}
