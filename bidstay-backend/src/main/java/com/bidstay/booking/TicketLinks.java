package com.bidstay.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class TicketLinks {
    private final String origin;

    TicketLinks(@Value("${bidstay.tickets.public-origin:}") String configured) {
        this.origin = configured.strip().replaceFirst("/$", "");
        if (!origin.isEmpty()) {
            URI url = URI.create(origin);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null
                    || !url.getPath().isEmpty() || url.getQuery() != null || url.getFragment() != null) {
                throw new IllegalArgumentException("BIDSTAY_PUBLIC_ORIGIN must be an HTTPS origin");
            }
        }
    }

    public boolean enabled() { return !origin.isEmpty(); }
    public String guest(long id) { return origin + "/ticket/" + id; }
}
