package com.bidstay.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PortalPage {
    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
    private static final String DEMO_DISABLED = "name=\"bidstay-demo\" content=\"disabled\"";
    private static final String DEMO_ENABLED = "name=\"bidstay-demo\" content=\"enabled\"";
    private final Resource index;
    private final boolean demo;

    PortalPage(@Value("classpath:/static/index.html") Resource index, Environment environment) {
        this.index = index;
        this.demo = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @GetMapping(value = {"/", "/index.html", "/hourly", "/trips", "/alerts", "/login", "/offers/{bidId}", "/checkout/{bookingId}", "/ticket/{bookingId}",
            "/hotel", "/hotel/**", "/admin", "/admin/**"}, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> show() throws IOException {
        if (!index.exists()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The BidStay frontend is missing from this build");
        }
        String html = index.getContentAsString(StandardCharsets.UTF_8);
        if (demo) html = html.replace(DEMO_DISABLED, DEMO_ENABLED);
        return ResponseEntity.ok().contentType(HTML_UTF8).cacheControl(CacheControl.noStore()).body(html);
    }
}
