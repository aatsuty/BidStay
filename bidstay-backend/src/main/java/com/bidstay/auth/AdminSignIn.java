package com.bidstay.auth;

import com.bidstay.audit.AuditTrail;
import com.bidstay.audit.AuditTrail.Action;
import com.bidstay.common.ApiException;
import com.bidstay.user.Role;
import com.bidstay.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

@Service
class AdminSignIn {
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AuthService auth;
    private final AuditTrail audit;
    private final Clock clock;
    private final String phone;
    private final String passwordHash;
    private final byte[] totpSecret;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    AdminSignIn(UserRepository users, JdbcTemplate jdbc, TransactionTemplate transactions,
                AuthService auth, AuditTrail audit, Clock clock,
                @Value("${bidstay.admin.phone:}") String phone,
                @Value("${bidstay.admin.password-hash:}") String passwordHash,
                @Value("${bidstay.admin.totp-secret:}") String totpSecret) {
        this.users = users;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.auth = auth;
        this.audit = audit;
        this.clock = clock;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.totpSecret = base32(totpSecret);
    }

    AuthService.SignedIn signIn(String caller, String password, String code) {
        if (phone.isBlank() || passwordHash.isBlank() || totpSecret.length < 16) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Admin sign-in is unavailable");
        }
        if (!phone.equals(caller)) throw invalid();
        var user = users.findByPhone(phone).filter(found -> found.getRole() == Role.ADMIN)
                .orElseThrow(AdminSignIn::invalid);
        Instant now = clock.instant();
        Timestamp time = Timestamp.from(now);
        int updated = jdbc.update("""
                insert into admin_auth_state (user_id, attempts, window_end) values (?, 1, ?)
                on conflict (user_id) do update
                set attempts = case when admin_auth_state.window_end <= ? then 1 else admin_auth_state.attempts + 1 end,
                    window_end = case when admin_auth_state.window_end <= ? then excluded.window_end else admin_auth_state.window_end end
                where admin_auth_state.window_end <= ? or admin_auth_state.attempts < 5
                """, user.getId(), Timestamp.from(now.plus(Duration.ofMinutes(10))), time, time, time);
        if (updated == 0) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Try again in ten minutes");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72 || !passwords.matches(password, passwordHash)) throw invalid();

        long current = now.getEpochSecond() / 30;
        byte[] submitted = code.getBytes(StandardCharsets.US_ASCII);
        for (long step = current - 1; step <= current + 1; step++) {
            if (!MessageDigest.isEqual(totp(step), submitted)) continue;
            long matched = step;
            return transactions.execute(transaction -> {
                int consumed = jdbc.update("""
                        update admin_auth_state set last_totp = ?, attempts = 0
                        where user_id = ? and last_totp < ?
                        """, matched, user.getId(), matched);
                if (consumed == 0) throw invalid();
                audit.record(user.getId(), Action.ADMIN_SIGNED_IN, user.getId(), null);
                return auth.issueSession(user);
            });
        }
        throw invalid();
    }

    private byte[] totp(long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(totpSecret, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = digest[digest.length - 1] & 15;
            int code = (ByteBuffer.wrap(digest, offset, 4).getInt() & 0x7fffffff) % 1_000_000;
            return String.format(Locale.ROOT, "%06d", code).getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] base32(String value) {
        String text = value.strip().toUpperCase(Locale.ROOT).replaceFirst("=+$", "");
        byte[] bytes = new byte[text.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char digit : text.toCharArray()) {
            int symbol = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(digit);
            if (symbol < 0) return new byte[0];
            buffer = (buffer << 5) | symbol;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes[index++] = (byte) (buffer >> bits);
            }
        }
        return Arrays.copyOf(bytes, index);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Check your password and authenticator code");
    }
}
