package com.bidstay.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "bidstay.admin.phone=9876500080",
        "bidstay.admin.totp-secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
})
@AutoConfigureMockMvc
@Testcontainers
class GuestAuthIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired com.bidstay.user.UserRepository users;
    @MockBean PhoneVerifier verifier;

    @DynamicPropertySource
    static void adminCredentials(DynamicPropertyRegistry properties) {
        String hash = new BCryptPasswordEncoder(4).encode("local-integration-password");
        properties.add("bidstay.admin.password-hash", () -> hash);
    }

    @Test
    void adminPasswordAndAuthenticatorCodeAreRequiredAndTheCodeCannotBeReplayed() throws Exception {
        jdbc.update("insert into users (phone, name, role) values ('9876500080', 'Admin', 'ADMIN')");
        long step = Instant.now().getEpochSecond() / 30;
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("12345678901234567890".getBytes(StandardCharsets.US_ASCII), "HmacSHA1"));
        byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        int offset = digest[digest.length - 1] & 15;
        String code = String.format(Locale.ROOT, "%06d", (ByteBuffer.wrap(digest, offset, 4).getInt() & 0x7fffffff) % 1_000_000);
        String endpoint = "/api/v1/auth/admin/sign-in";
        String credentials = json.writeValueAsString(java.util.Map.of(
                "phone", "9876500080", "password", "local-integration-password", "code", code));
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                .content(credentials.replace("local-integration-password", "wrong")))
                .andExpect(status().isUnauthorized());
        String response = mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/admin/stats").header("Authorization", "Bearer " + json.readTree(response).get("token").asText()))
                .andExpect(status().isOk());
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestVerifiesPhoneAndCanRevokeTheirSession() throws Exception {
        String phone = "9876500010";
        when(verifier.send(phone)).thenReturn("VEguest");
        when(verifier.verify("VEguest", "123456")).thenReturn(true);

        mvc.perform(post("/api/v1/auth/guest/code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/guest/code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}")).andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/auth/guest/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"code\":\"000000\"}")).andExpect(status().isUnauthorized());

        String verification = "{\"phone\":\"" + phone + "\",\"code\":\"123456\",\"name\":\"Demo Guest\"}";
        String response = mvc.perform(post("/api/v1/auth/guest/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(verification))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.guest.name").value("Demo Guest"))
                .andReturn().getResponse().getContentAsString();
        String authorization = "Bearer " + json.readTree(response).get("token").asText();

        mvc.perform(get("/api/v1/me").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("GUEST"));
        mvc.perform(get("/api/v1/bids").header("Authorization", authorization))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/stats").header("Authorization", authorization))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/guest/verify").contentType(MediaType.APPLICATION_JSON)
                .content(verification)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", authorization))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/bids").header("Authorization", authorization))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifiedStaffInviteGrantsOnlyTheNamedHotel() throws Exception {
        long hotel = addHotel("Inviting Hotel");
        long other = addHotel("Another Hotel");
        long owner = jdbc.queryForObject("""
                insert into users (phone, name, role) values ('9876500070', 'Host', 'HOTEL_STAFF') returning id
                """, Long.class);
        jdbc.update("insert into hotel_staff(user_id, hotel_id) values (?, ?)", owner, hotel);
        String ownerAuthorization = "Bearer " + auth.issueSession(users.findById(owner).orElseThrow()).token();
        String phone = "9876500071";
        when(verifier.send(phone)).thenReturn("VEinvited");
        when(verifier.verify("VEinvited", "123456")).thenReturn(true);
        var request = "{\"phone\":\"" + phone + "\"}";
        mvc.perform(post("/api/v1/partner/hotels/{id}/invites", other)
                .header("Authorization", ownerAuthorization).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        String invitation = mvc.perform(post("/api/v1/partner/hotels/{id}/invites", hotel)
                .header("Authorization", ownerAuthorization).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(invitation).get("token").asText();

        mvc.perform(post("/api/v1/auth/staff/code").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNoContent());
        String signedIn = mvc.perform(post("/api/v1/auth/staff/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("phone", phone, "code", "123456", "invite", token))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String staffAuthorization = "Bearer " + json.readTree(signedIn).get("token").asText();
        mvc.perform(get("/api/v1/partner/hotels").header("Authorization", staffAuthorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(hotel));
        mvc.perform(get("/api/v1/partner/hotels/{id}/offers", other).header("Authorization", staffAuthorization))
                .andExpect(status().isForbidden());
        assertEquals(1, jdbc.queryForObject("select count(*) from staff_invites where accepted_at is not null", Integer.class));
    }

    private long addHotel(String name) {
        return jdbc.queryForObject("insert into hotels(name, city, area, address) values (?, 'Delhi', 'Paharganj', 'Private') returning id",
                Long.class, name);
    }

    @Test
    void adminNeedsStaffSignInAndDemoHeaderIsRejectedOutsideDev() throws Exception {
        String phone = "9876500011";
        Long admin = jdbc.queryForObject("""
                insert into users (phone, name, role) values (?, 'Administrator', 'ADMIN') returning id
                """, Long.class, phone);
        when(verifier.send(phone)).thenReturn("VEadmin");
        when(verifier.verify("VEadmin", "123456")).thenReturn(true);

        mvc.perform(get("/api/v1/admin/stats").header("X-User-Id", admin))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/guest/code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/guest/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"code\":\"123456\"}")).andExpect(status().isForbidden());
    }
}
