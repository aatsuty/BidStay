package com.bidstay.booking;

import com.bidstay.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TicketContacts {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JdbcTemplate jdbc;
    private final TicketProviders providers;
    private final TicketLinks links;
    private final TransactionTemplate transactions;

    Contact contact(long guestId) {
        return jdbc.queryForObject("""
                select c.email, coalesce(c.sms_enabled, true), u.guest_phone_verified
                from users u left join guest_ticket_contacts c on c.guest_id = u.id where u.id = ?
                """, (rs, row) -> new Contact(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3),
                links.enabled() && providers.smsEnabled(), links.enabled() && providers.emailEnabled()), guestId);
    }

    Contact setSms(long guestId, boolean enabled) {
        jdbc.update("""
                insert into guest_ticket_contacts(guest_id, sms_enabled) values (?, ?)
                on conflict (guest_id) do update set sms_enabled = excluded.sms_enabled
                """, guestId, enabled);
        return contact(guestId);
    }

    void sendEmailCode(long guestId, String address) {
        if (!links.enabled() || !providers.emailEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery is unavailable.");
        String email = address.strip();
        String key = email.toLowerCase(Locale.ROOT);
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String hash = encoder.encode(code);
        transactions.executeWithoutResult(tx -> {
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", (rs, row) -> 0, "ticket-email:" + key);
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from guest_ticket_contacts
                    where lower(pending_email) = ? and resend_at > now())
                    """, Boolean.class, key))) throw cooldown();
            int reserved = jdbc.update("""
                    insert into guest_ticket_contacts(guest_id, pending_email, code_hash, code_expires_at, resend_at)
                    values (?, ?, ?, now() + interval '10 minutes', now() + interval '1 minute')
                    on conflict (guest_id) do update set pending_email = excluded.pending_email,
                      code_hash = excluded.code_hash, code_expires_at = excluded.code_expires_at,
                      resend_at = excluded.resend_at, code_attempts = 0
                    where guest_ticket_contacts.resend_at is null or guest_ticket_contacts.resend_at <= now()
                    """, guestId, email, hash);
            if (reserved == 0) throw cooldown();
        });
        try {
            providers.email(email, "Verify your BidStay ticket email",
                    "<h1>BidStay email verification</h1><p>Your code is <strong>" + code
                            + "</strong>. Enter it in BidStay within 10 minutes.</p>", "ticket-email-code-" + UUID.randomUUID());
        } catch (RuntimeException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "We couldn't send the code. Try again in a minute.");
        }
    }

    Contact verifyEmail(long guestId, String code) {
        // Count attempts even when the supplied code fails or another request consumes the challenge.
        var hashes = jdbc.query("""
                update guest_ticket_contacts set code_attempts = code_attempts + 1
                where guest_id = ? and code_hash is not null and code_expires_at > now() and code_attempts < 5
                returning code_hash
                """, (rs, row) -> rs.getString(1), guestId);
        if (hashes.isEmpty() || !encoder.matches(code, hashes.getFirst())) throw invalidCode();
        int changed = jdbc.update("""
                update guest_ticket_contacts set email = pending_email, code_hash = null, code_expires_at = null
                where guest_id = ? and code_hash = ? and code_expires_at > now()
                """, guestId, hashes.getFirst());
        if (changed == 0) throw invalidCode();
        return contact(guestId);
    }

    Contact removeEmail(long guestId) {
        jdbc.update("""
                update guest_ticket_contacts set email = null, code_hash = null, code_expires_at = null,
                pending_email = case when resend_at > now() then pending_email end
                where guest_id = ?
                """, guestId);
        return contact(guestId);
    }

    private ApiException cooldown() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Wait one minute before requesting another email code.");
    }
    private ApiException invalidCode() { return ApiException.invalid("Incorrect or expired email code."); }
    record Contact(String email, boolean smsEnabled, boolean phoneVerified, boolean smsAvailable, boolean emailAvailable) {}
}
