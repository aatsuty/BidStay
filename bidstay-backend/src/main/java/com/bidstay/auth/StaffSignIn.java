package com.bidstay.auth;

import com.bidstay.common.ApiException;
import com.bidstay.user.Role;
import com.bidstay.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
class StaffSignIn {
    private final AuthService auth;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    Invitation invite(Long userId, Long hotelId, String phone) {
        var existing = users.findByPhone(phone);
        if (existing.isPresent() && existing.get().getRole() != Role.HOTEL_STAFF) {
            throw ApiException.conflict("Use a phone number reserved for the hotel staff account");
        }
        String token = AuthService.newToken();
        Instant expiresAt = clock.instant().plus(Duration.ofHours(48));
        jdbc.update("""
                insert into staff_invites (token_hash, hotel_id, phone, created_by, expires_at)
                values (?, ?, ?, ?, ?)
                """, AuthService.hash(token), hotelId, phone, userId, Timestamp.from(expiresAt));
        return new Invitation(token, expiresAt);
    }

    AuthService.SignedIn verify(String phone, String code, String name, String inviteToken) {
        return auth.verifyPhone(phone, code, () -> {
            if (inviteToken != null) {
                var invite = jdbc.query("""
                        select hotel_id from staff_invites where token_hash = ? and phone = ?
                          and accepted_at is null and expires_at > ? for update
                        """, (rs, row) -> rs.getLong(1), AuthService.hash(inviteToken), phone,
                        Timestamp.from(clock.instant()));
                if (invite.isEmpty()) return null;
                jdbc.update("""
                        insert into users (phone, name, role) values (?, ?, 'HOTEL_STAFF')
                        on conflict (phone) do nothing
                        """, phone, AuthService.displayName(name, "Hotel partner"));
                var user = users.findByPhone(phone).orElseThrow();
                if (user.getRole() != Role.HOTEL_STAFF) return null;
                jdbc.update("insert into hotel_staff (user_id, hotel_id) values (?, ?) on conflict do nothing",
                        user.getId(), invite.getFirst());
                jdbc.update("update staff_invites set accepted_at = ?, accepted_by = ? where token_hash = ?",
                        Timestamp.from(clock.instant()), user.getId(), AuthService.hash(inviteToken));
                return auth.issueSession(user);
            }
            return users.findByPhone(phone).filter(user -> user.getRole() == Role.HOTEL_STAFF)
                    .map(auth::issueSession).orElse(null);
        });
    }

    static ApiException rejected() {
        return new ApiException(HttpStatus.FORBIDDEN, "This phone has no hotel staff account or the invitation is invalid");
    }

    record Invitation(String token, Instant expiresAt) {}
}
