package com.bidstay.auth;

import com.bidstay.common.ApiException;
import com.bidstay.user.Role;
import com.bidstay.user.User;
import com.bidstay.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
class AuthService {
    private static final Duration RESEND_DELAY = Duration.ofMinutes(1);
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    private static final Duration SESSION_LIFETIME = Duration.ofDays(7);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UserRepository users;
    private final PhoneVerifier verifier;
    private final Clock clock;

    void sendCode(String phone) {
        Instant now = clock.instant();
        Timestamp resendAt = Timestamp.from(now.plus(RESEND_DELAY));
        int reserved = jdbc.update("""
                insert into phone_verifications (phone, resend_at, expires_at) values (?, ?, ?)
                on conflict (phone) do update
                set resend_at = excluded.resend_at, expires_at = excluded.expires_at, verification_sid = null
                where phone_verifications.resend_at <= ?
                """, phone, resendAt, Timestamp.from(now.plus(CODE_LIFETIME)), Timestamp.from(now));
        if (reserved == 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Wait one minute before requesting another code.");
        }
        String verificationSid = verifier.send(phone);
        jdbc.update("update phone_verifications set verification_sid = ? where phone = ? and resend_at = ?",
                verificationSid, phone, resendAt);
    }

    SignedIn verifyCode(String phone, String code, String name) {
        return verifyPhone(phone, code, () -> {
            jdbc.update("""
                    insert into users (phone, name, role) values (?, ?, 'GUEST')
                    on conflict (phone) do nothing
                    """, phone, displayName(name, "Guest"));
            var user = users.findByPhone(phone).orElseThrow();
            if (user.getRole() != Role.GUEST) return null;
            jdbc.update("update users set guest_phone_verified = true where id = ? and not guest_phone_verified", user.getId());
            return issueSession(user);
        });
    }

    SignedIn verifyPhone(String phone, String code, Supplier<SignedIn> signIn) {
        String sid = jdbc.query("""
                select verification_sid from phone_verifications where phone = ? and expires_at > ?
                """, (rs, i) -> rs.getString(1), phone, Timestamp.from(clock.instant()))
                .stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow(AuthService::invalidCode);
        if (!verifier.verify(sid, code)) throw invalidCode();

        return transactions.execute(status -> {
            int consumed = jdbc.update("""
                    update phone_verifications set verification_sid = null
                    where phone = ? and verification_sid = ? and expires_at > ?
                    """, phone, sid, Timestamp.from(clock.instant()));
            if (consumed == 0) throw invalidCode();
            return signIn.get();
        });
    }

    SignedIn issueSession(User user) {
        String token = newToken();
        Instant expiresAt = clock.instant().plus(user.getRole() == Role.ADMIN ? Duration.ofHours(8) : SESSION_LIFETIME);
        jdbc.update("insert into auth_sessions (token_hash, user_id, expires_at, scope_role) values (?, ?, ?, ?)",
                hash(token), user.getId(), Timestamp.from(expiresAt), user.getRole().name());
        return new SignedIn(token, expiresAt, new Guest(user.getId(), user.getName(), user.getPhone()));
    }

    static String displayName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name.strip();
    }

    static String newToken() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    Long sessionUserId(String authorization) {
        String hash = authorizationHash(authorization);
        return jdbc.query("""
                select s.user_id from auth_sessions s join users u on u.id = s.user_id
                where s.token_hash = ? and s.expires_at > ? and u.role = s.scope_role
                  and (u.role <> 'HOTEL_STAFF' or exists (select 1 from hotel_staff h where h.user_id = u.id))
                """, (rs, i) -> rs.getLong(1), hash, Timestamp.from(clock.instant()))
                .stream().findFirst().orElseThrow(AuthService::signInRequired);
    }

    void signOut(String authorization) {
        jdbc.update("delete from auth_sessions where token_hash = ?", authorizationHash(authorization));
    }

    @Scheduled(fixedDelayString = "PT5M")
    void clearExpired() {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                delete from auth_sessions where token_hash in
                (select token_hash from auth_sessions where expires_at <= ? order by expires_at limit 1000)
                """, now);
        jdbc.update("""
                delete from phone_verifications where phone in
                (select phone from phone_verifications where expires_at <= ? order by expires_at limit 1000)
                and expires_at <= ?
                """, now, now);
    }

    private String authorizationHash(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw signInRequired();
        }
        String token = authorization.substring(7);
        if (!TOKEN.matcher(token).matches()) throw signInRequired();
        return hash(token);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static ApiException signInRequired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue.");
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "The code is incorrect or expired.");
    }

    record Guest(Long id, String name, String phone) {}
    record SignedIn(String token, Instant expiresAt, Guest guest) {}
}
