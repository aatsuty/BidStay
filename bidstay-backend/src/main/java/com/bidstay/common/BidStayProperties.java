package com.bidstay.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "bidstay")
public record BidStayProperties(
        Duration offerResponseWindow,
        Duration bidLifetime,
        Duration paymentHold,
        int maxRounds,
        int maxHotelsPerBid,
        int maxNights,
        BigDecimal commissionPct) {
}
