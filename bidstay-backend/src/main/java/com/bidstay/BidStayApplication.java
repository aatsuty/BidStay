package com.bidstay;

import com.bidstay.common.BidStayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BidStayProperties.class)
public class BidStayApplication {

    public static void main(String[] args) {
        SpringApplication.run(BidStayApplication.class, args);
    }

    /** Injected everywhere instead of Instant.now() so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
