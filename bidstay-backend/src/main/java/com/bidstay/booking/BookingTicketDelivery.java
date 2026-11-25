package com.bidstay.booking;

import com.bidstay.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
class BookingTicketDelivery {
    private final JdbcTemplate jdbc;
    private final TicketLinks links;
    private final TicketProviders providers;
    private final TransactionTemplate transactions;
    private final Clock clock;

    void enqueue(long guestId, long bookingId) {
        if (!links.enabled()) return;
        jdbc.update("""
                insert into booking_ticket_outbox(booking_id, channel, destination, send_before)
                select b.id, contacts.channel, contacts.destination, now() + interval '1 day'
                from bookings b join users u on u.id = b.guest_id and u.role = 'GUEST'
                left join guest_ticket_contacts c on c.guest_id = u.id
                cross join lateral (
                    select 'SMS' as channel, u.phone as destination
                    where ? and u.guest_phone_verified and coalesce(c.sms_enabled, true)
                      and u.phone ~ '^[6-9][0-9]{9}$'
                    union all select 'EMAIL', c.email where ? and c.email is not null
                ) contacts
                where b.id = ? and b.guest_id = ?
                  and exists (select 1 from payment_captures p where p.booking_id = b.id and p.status = 'APPLIED')
                on conflict (booking_id, channel) do nothing
                """, providers.smsEnabled(), providers.emailEnabled(), bookingId, guestId);
    }

    List<DeliveryStatus> statuses(long bookingId) {
        return jdbc.query("""
                select channel, state from booking_ticket_outbox where booking_id = ? order by channel
                """, (rs, row) -> new DeliveryStatus(rs.getString(1), rs.getString(2)), bookingId);
    }

    @Scheduled(fixedDelayString = "PT2S")
    void deliver() {
        if (!links.enabled() || !(providers.smsEnabled() || providers.emailEnabled())) return;
        transactions.executeWithoutResult(transaction -> jdbc.query("""
                select q.booking_id, q.channel, q.destination, q.attempts, b.guest_id,
                       q.send_before > now() and b.status = 'CONFIRMED' and u.role = 'GUEST'
                         and case q.channel when 'SMS' then
                           u.guest_phone_verified and u.phone = q.destination and coalesce(c.sms_enabled, true)
                         else c.email is not null and c.email = q.destination end
                from booking_ticket_outbox q join bookings b on b.id = q.booking_id join users u on u.id = b.guest_id
                left join guest_ticket_contacts c on c.guest_id = u.id
                where q.state = 'QUEUED' and q.next_attempt_at <= now()
                order by q.next_attempt_at, q.booking_id, q.channel limit 1 for update of q skip locked
                """, (rs, i) -> new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5),
                rs.getBoolean(6))).stream().findFirst().ifPresent(this::send));
    }

    private void send(Job job) {
        boolean sms = job.channel().equals("SMS");
        if (!job.allowed() || (sms ? !providers.smsEnabled() : !providers.emailEnabled())) {
            update(job, "SKIPPED", null, job.attempts());
            return;
        }
        try {
            String url = links.guest(job.bookingId());
            String id = sms ? providers.sms(job.destination(), "BidStay booking #" + job.bookingId()
                    + " confirmed. View your ticket: " + url)
                    : sendEmail(job, url);
            update(job, "SUBMITTED", id, job.attempts());
        } catch (ApiException missing) {
            update(job, "SKIPPED", null, job.attempts());
        } catch (RuntimeException failure) {
            int attempts = job.attempts() + 1;
            boolean permanent = failure instanceof RestClientResponseException response
                    && response.getStatusCode().is4xxClientError() && response.getStatusCode().value() != 429;
            update(job, permanent || attempts >= 6 ? "FAILED" : "QUEUED", null, attempts);
        }
    }

    private String sendEmail(Job job, String url) {
        // The ticket service reads the same stored terms and receipt used by the guest portal.
        return providers.email(job.destination(), "BidStay ticket #" + job.bookingId(),
                ticketReader.getObject().render(job.guestId(), job.bookingId(), url), "booking-ticket-" + job.bookingId());
    }

    // Lazy supplier avoids a dependency cycle with BookingService, which queues confirmations.
    private final org.springframework.beans.factory.ObjectProvider<BookingTickets> ticketReader;

    private void update(Job job, String state, String id, int attempts) {
        jdbc.update("""
                update booking_ticket_outbox set state = ?, provider_id = ?, attempts = ?,
                  next_attempt_at = ? where booking_id = ? and channel = ?
                """, state, id, attempts, java.sql.Timestamp.from(clock.instant().plusSeconds(10L << attempts)),
                job.bookingId(), job.channel());
    }

    record DeliveryStatus(String channel, String state) {}
    private record Job(long bookingId, String channel, String destination, int attempts, long guestId, boolean allowed) {}
}
