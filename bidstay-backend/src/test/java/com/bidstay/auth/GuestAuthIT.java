package com.bidstay.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GuestAuthIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockBean PhoneVerifier verifier;

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
