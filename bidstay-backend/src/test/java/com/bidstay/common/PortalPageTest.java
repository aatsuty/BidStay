package com.bidstay.common;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PortalPageTest {
    private static final ByteArrayResource INDEX = new ByteArrayResource(
            "<meta name=\"bidstay-demo\" content=\"disabled\"><title>BidStay</title>".getBytes(StandardCharsets.UTF_8));

    @ParameterizedTest
    @ValueSource(strings = {"/", "/hourly", "/checkout/42", "/ticket/42", "/hotel/rates", "/admin/market"})
    void servesPortalAtBrowserRoutes(String route) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new PortalPage(INDEX, new MockEnvironment())).build();
        mvc.perform(get(route)).andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("<title>BidStay</title>")))
                .andExpect(content().string(containsString("content=\"disabled\"")));
    }

    @Test
    void enablesDemoUiInDevAndLeavesUnmatchedApiPathsAlone() throws Exception {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        var mvc = MockMvcBuilders.standaloneSetup(new PortalPage(INDEX, environment)).build();
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("content=\"enabled\"")));
        mvc.perform(get("/api/v1/missing")).andExpect(status().isNotFound());
    }
}
