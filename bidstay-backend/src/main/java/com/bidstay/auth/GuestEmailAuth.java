package com.bidstay.auth;

import com.bidstay.common.ApiException;
import com.bidstay.common.ResendEmail;
import com.bidstay.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
class GuestEmailAuth {
    private static final Logger LOG = LoggerFactory.getLogger(GuestEmailAuth.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration RESEND_DELAY = Duration.ofMinutes(1);
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 5;
    private static final String HMAC = "HmacSHA256";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AuthService auth;
    private final UserRepository users;
    private final ResendEmail email;
    private final Clock clock;
    private final String sender;
    private final SecretKeySpec codeKey;

    GuestEmailAuth(JdbcTemplate jdbc, TransactionTemplate transactions, AuthService auth, UserRepository users,
                   ResendEmail email, Clock clock,
                   @Value("${bidstay.auth.guest-email-from:}") String sender,
                   @Value("${bidstay.auth.guest-email-secret:}") String secret) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.auth = auth;
        this.users = users;
        this.email = email;
        this.clock = clock;
        this.sender = sender.strip();
        byte[] key = secret.strip().getBytes(StandardCharsets.UTF_8);
        codeKey = key.length >= 32 ? new SecretKeySpec(key, HMAC) : null;
    }

    boolean enabled() {
        return email.enabled() && !sender.isBlank() && codeKey != null;
    }

    String linkedEmail(long guestId) {
        return jdbc.queryForObject("select guest_sign_in_email from users where id = ? and role = 'GUEST'",
                String.class, guestId);
    }

    UUID sendCode(String address, Long linkGuestId) {
        requireEnabled();
        if (linkGuestId != null && linkedEmail(linkGuestId) != null) throw linkConflict();
        String recipient = normalize(address);
        var now = clock.instant();
        UUID challengeId = UUID.randomUUID();
        int reserved = jdbc.update("""
                insert into guest_email_codes(email, challenge_id, link_guest_id, resend_at, expires_at)
                values (?, ?, ?, ?, ?)
                on conflict (email) do update
                set challenge_id = excluded.challenge_id, link_guest_id = excluded.link_guest_id,
                    resend_at = excluded.resend_at, expires_at = excluded.expires_at, code_digest = null, attempts = 0
                where guest_email_codes.resend_at <= ?
                """, recipient, challengeId, linkGuestId, Timestamp.from(now.plus(RESEND_DELAY)),
                Timestamp.from(now.plus(CODE_LIFETIME)), Timestamp.from(now));
        if (reserved == 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Wait one minute before requesting another code.");
        }

        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        boolean linking = linkGuestId != null;
        try {
            email.send(sender, recipient, linking ? "Link your email to BidStay" : "Your BidStay sign-in code", """
                    <p>Your BidStay code to %s is <strong>%s</strong>.</p>
                    <p>It expires in 10 minutes. Enter it only on BidStay and never share it.
                    If you did not request this code, you can ignore this email.</p>
                    """.formatted(linking ? "link this email" : "sign in", code), "guest-auth/" + challengeId);
        } catch (RestClientResponseException failure) {
            // Provider bodies can contain the recipient or other private data.
            LOG.warn("Guest email delivery rejected with HTTP {}", failure.getStatusCode().value());
            throw deliveryUnavailable();
        } catch (RestClientException | IllegalStateException failure) {
            LOG.warn("Guest email delivery failed ({})", failure.getClass().getSimpleName());
            throw deliveryUnavailable();
        }

        jdbc.update("update guest_email_codes set code_digest = ? where email = ? and challenge_id = ?",
                digest(challengeId, code), recipient, challengeId);
        return challengeId;
    }

    AuthService.SignedIn signIn(String address, UUID challengeId, String code, String name) {
        requireEnabled();
        String recipient = normalize(address);
        var session = transactions.execute(status -> {
            if (!consumeCode(recipient, challengeId, code, null)) return null;
            jdbc.update("""
                    insert into users (guest_sign_in_email, name, role) values (?, ?, 'GUEST')
                    on conflict (guest_sign_in_email) do nothing
                    """, recipient, AuthService.displayName(name, "Guest"));
            Long userId = jdbc.queryForObject("select id from users where guest_sign_in_email = ? and role = 'GUEST'",
                    Long.class, recipient);
            return auth.issueSession(users.findById(userId).orElseThrow());
        });
        if (session == null) throw invalidCode();
        return session;
    }

    void link(String address, UUID challengeId, String code, long guestId) {
        requireEnabled();
        String recipient = normalize(address);
        LinkResult result = transactions.execute(status -> {
            if (!consumeCode(recipient, challengeId, code, guestId)) return LinkResult.INVALID_CODE;
            int updated = jdbc.update("""
                    update users set guest_sign_in_email = ?
                    where id = ? and role = 'GUEST' and guest_sign_in_email is null
                      and not exists (select 1 from users where guest_sign_in_email = ?)
                    """, recipient, guestId, recipient);
            return updated == 1 ? LinkResult.LINKED : LinkResult.CONFLICT;
        });
        if (result == LinkResult.INVALID_CODE) throw invalidCode();
        if (result == LinkResult.CONFLICT) throw linkConflict();
    }

    // The caller holds the transaction until account changes and code consumption have committed together.
    private boolean consumeCode(String address, UUID challengeId, String code, Long guestId) {
        var challenge = jdbc.query("""
                select link_guest_id, code_digest, attempts, expires_at
                from guest_email_codes where email = ? and challenge_id = ? for update
                """, (rs, row) -> new Challenge(rs.getObject(1, Long.class), rs.getBytes(2), rs.getInt(3),
                rs.getTimestamp(4)), address, challengeId).stream().findFirst().orElse(null);
        if (challenge == null || challenge.digest() == null || !Objects.equals(challenge.guestId(), guestId)
                || challenge.attempts() >= MAX_ATTEMPTS || !challenge.expiresAt().toInstant().isAfter(clock.instant())) {
            return false;
        }
        if (!MessageDigest.isEqual(challenge.digest(), digest(challengeId, code))) {
            jdbc.update("update guest_email_codes set attempts = attempts + 1 where email = ?", address);
            return false;
        }
        jdbc.update("update guest_email_codes set code_digest = null where email = ?", address);
        return true;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT5M")
    void clearExpired() {
        jdbc.update("""
                delete from guest_email_codes where email in
                (select email from guest_email_codes where expires_at <= ?
                 order by expires_at limit 1000 for update skip locked)
                """, Timestamp.from(clock.instant()));
    }

    private byte[] digest(UUID challengeId, String code) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(codeKey);
            return mac.doFinal((challengeId + ":" + code).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not protect guest email code", failure);
        }
    }

    private static String normalize(String email) {
        return email.toLowerCase(Locale.ROOT);
    }

    private void requireEnabled() {
        if (!enabled()) throw deliveryUnavailable();
    }

    private static ApiException deliveryUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Email verification is unavailable. Try again later or use mobile sign-in.");
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "The code is incorrect or expired. Request a new code if needed.");
    }

    private static ApiException linkConflict() {
        return new ApiException(HttpStatus.CONFLICT, "This email could not be linked. Each guest account can have one sign-in email.");
    }

    private record Challenge(Long guestId, byte[] digest, int attempts, Timestamp expiresAt) {}
    private enum LinkResult { LINKED, INVALID_CODE, CONFLICT }
}
