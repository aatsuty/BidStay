package com.bidstay.auth;

import com.bidstay.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
class AuthRateLimits implements HandlerInterceptor, WebMvcConfigurer {
    private static final long WINDOW_SECONDS = 600;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int sends;
    private final int checks;
    private final int adminAttempts;

    AuthRateLimits(JdbcTemplate jdbc, Clock clock,
                   @Value("${bidstay.rate-limit.otp-send:20}") int sends,
                   @Value("${bidstay.rate-limit.otp-verify:60}") int checks,
                   @Value("${bidstay.rate-limit.admin-sign-in:20}") int adminAttempts) {
        if (sends < 1 || checks < 1 || adminAttempts < 1) {
            throw new IllegalArgumentException("Sign-in rate limits must be positive");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.sends = sends;
        this.checks = checks;
        this.adminAttempts = adminAttempts;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/auth/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) return true;
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (route == null) return true;

        Limit limit = switch (route.toString()) {
            case "/api/v1/auth/guest/code", "/api/v1/auth/staff/code" -> new Limit("otp-send", sends);
            case "/api/v1/auth/guest/verify", "/api/v1/auth/staff/verify" -> new Limit("otp-verify", checks);
            case "/api/v1/auth/admin/sign-in" -> new Limit("admin-sign-in", adminAttempts);
            default -> null;
        };
        if (limit == null) return true;

        Instant now = clock.instant();
        long nextWindow = (now.getEpochSecond() / WINDOW_SECONDS + 1) * WINDOW_SECONDS;
        Instant ends = Instant.ofEpochSecond(nextWindow);
        // The servlet container supplies the client address. IPv6 addresses share one bucket per /64.
        int changed = jdbc.update("""
                insert into auth_rate_limits(scope, client, window_end, hits)
                select ?, network(set_masklen(address, case family(address) when 6 then 64 else 32 end)), ?, 1
                from (select cast(? as inet) as address) connection
                on conflict (scope, client) do update
                set window_end = excluded.window_end,
                    hits = case when auth_rate_limits.window_end < excluded.window_end then 1 else auth_rate_limits.hits + 1 end
                where auth_rate_limits.window_end < excluded.window_end
                   or (auth_rate_limits.window_end = excluded.window_end and auth_rate_limits.hits < ?)
                """, limit.scope(), Timestamp.from(ends), request.getRemoteAddr(), limit.max());
        if (changed == 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, Duration.between(now, ends).toSeconds() + 1)));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in requests. Try again shortly.");
        }
        return true;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
    void clearExpired() {
        jdbc.update("""
                delete from auth_rate_limits where (scope, client) in
                (select scope,client from auth_rate_limits where window_end < ?
                 order by window_end limit 2000 for update skip locked)
                """, Timestamp.from(clock.instant().minus(Duration.ofHours(1))));
    }

    private record Limit(String scope, int max) {}
}
