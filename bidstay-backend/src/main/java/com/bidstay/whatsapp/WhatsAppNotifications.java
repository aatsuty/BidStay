package com.bidstay.whatsapp;

import com.bidstay.booking.BookingView;
import com.bidstay.booking.TicketLinks;
import com.bidstay.common.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.bidstay.inventory.InventoryService.STAY_ZONE;

@Service
@RequiredArgsConstructor
public class WhatsAppNotifications {
    private static final DateTimeFormatter TICKET_TIME = DateTimeFormatter.ofPattern("d MMM uuuu HH:mm", Locale.ENGLISH).withZone(STAY_ZONE);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WhatsAppClient client;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final TicketLinks ticketLinks;

    @Value("${bidstay.whatsapp.hotel-bid-template:}") private String hotelBidTemplate;
    @Value("${bidstay.whatsapp.guest-offer-template:}") private String guestOfferTemplate;
    @Value("${bidstay.whatsapp.booking-template:}") private String bookingTemplate;
    @Value("${bidstay.whatsapp.guest-ticket-template:}") private String guestTicketTemplate;

    boolean enabled() { return client.enabled(); }

    void setConsent(Long userId, Long hotelId, boolean enabled) {
        jdbc.update("""
                insert into whatsapp_consents (user_id, hotel_id, enabled) values (?, ?, ?)
                on conflict (user_id, hotel_id) do update set enabled = excluded.enabled, changed_at = now()
                """, userId, hotelId, enabled);
    }

    boolean consent(Long userId, Long hotelId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from whatsapp_consents where user_id = ?
                  and hotel_id is not distinct from cast(? as bigint) and enabled)
                """, Boolean.class, userId, hotelId));
    }

    public void hotelBid(Long hotelId, Long messageId, Long offerId, int round, BigDecimal price,
                         LocalDate arrival, LocalDate departure, int rooms, Instant deadline) {
        if (!enabled() || hotelBidTemplate.isBlank()) return;
        String reference = offerId + "/" + round;
        enqueueForHotel(hotelId, "bid-" + reference, hotelBidTemplate,
                encode(List.of(reference, arrival + " to " + departure, String.valueOf(rooms), Money.inr(price))),
                messageId, offerId, round, deadline);
    }

    public void guestOffer(Long guestId, Long bidId, Long offerId, int round, BigDecimal price, Instant deadline) {
        if (!enabled() || guestOfferTemplate.isBlank()) return;
        enqueueForGuest(guestId, "offer-" + offerId + "/" + round, guestOfferTemplate,
                encode(List.of(bidId.toString(), Money.inr(price))), offerId, round, deadline);
    }

    public void booked(Long guestId, Long hotelId, BookingView booking, Long messageId) {
        if (!enabled()) return;
        guestTicket(guestId, booking);
        if (bookingTemplate.isBlank()) return;
        Long bookingId = booking.bookingId();
        Instant deadline = clock.instant().plusSeconds(86_400);
        String event = "booking-" + bookingId;
        String contents = encode(List.of(bookingId.toString()));
        if (!ticketAvailable()) enqueueForGuest(guestId, event, bookingTemplate, contents, null, null, deadline);
        enqueueForHotel(hotelId, event, bookingTemplate, contents, messageId, null, null, deadline);
    }

    private boolean ticketAvailable() { return enabled() && ticketLinks.enabled() && !guestTicketTemplate.isBlank(); }

    public void guestTicket(Long guestId, BookingView booking) {
        if (!ticketAvailable()) return;
        long id = booking.bookingId();
        var shortStay = booking.shortStay();
        String dates = shortStay == null ? booking.checkIn() + " to " + booking.checkOut()
                : TICKET_TIME.format(shortStay.arrival()) + " to " + TICKET_TIME.format(shortStay.departure()) + " IST";
        String stay = dates + " · Rooms " + booking.rooms() + " · Paid " + Money.inrExact(booking.totalAmount());
        jdbc.update("""
                insert into whatsapp_outbox (consent_id, event_key, template_name, content, send_before, ticket_booking_id)
                select id, ?, ?, ?, ?, ? from whatsapp_consents where user_id = ? and hotel_id is null and enabled
                on conflict (consent_id, event_key) do nothing
                """, "ticket-" + id, guestTicketTemplate,
                encode(List.of(String.valueOf(id), booking.hotelName(), stay, ticketLinks.guest(id))),
                Timestamp.from(clock.instant().plusSeconds(86_400)), id, guestId);
    }

    public Optional<String> guestTicketState(long guestId, long bookingId) {
        return jdbc.query("""
                select upper(coalesce(d.status, q.state)) from whatsapp_outbox q
                join whatsapp_consents c on c.id = q.consent_id
                left join whatsapp_delivery d on d.provider_message_id = q.provider_message_id
                where c.user_id = ? and c.hotel_id is null and q.event_key = ?
                """, (rs, row) -> rs.getString(1), guestId, "ticket-" + bookingId).stream().findFirst();
    }

    void stop(String phone, String messageId) {
        jdbc.update("""
                with first_seen as (
                    insert into whatsapp_opt_out_events(provider_message_id) values (?)
                    on conflict do nothing returning provider_message_id
                )
                update whatsapp_consents c set enabled = false, changed_at = now()
                from users u where u.id = c.user_id and u.phone = ? and c.enabled
                   and exists (select 1 from first_seen)
                """, messageId, phone);
    }

    void reply(long consent, String messageId, String text, Instant deadline) {
        jdbc.update("""
                insert into whatsapp_outbox (consent_id, event_key, content, send_before)
                values (?, ?, ?, ?) on conflict (consent_id, event_key) do nothing
                """, consent, messageId, text, Timestamp.from(deadline));
    }

    private void enqueueForGuest(Long userId, String event, String template, String contents,
                                 Long offerId, Integer round, Instant deadline) {
        jdbc.update("""
                insert into whatsapp_outbox (consent_id, event_key, template_name, content, offer_id, round, send_before)
                select id, ?, ?, ?, ?, ?, ? from whatsapp_consents
                where user_id = ? and hotel_id is null and enabled
                on conflict (consent_id, event_key) do nothing
                """, event, template, contents, offerId, round, Timestamp.from(deadline), userId);
    }

    private void enqueueForHotel(Long hotelId, String event, String template, String contents, Long messageId,
                                 Long offerId, Integer round, Instant deadline) {
        jdbc.update("""
                insert into whatsapp_outbox
                  (consent_id, event_key, template_name, content, partner_message_id, offer_id, round, send_before)
                select c.id, ?, ?, ?, ?, ?, ?, ? from whatsapp_consents c
                join hotel_staff hs on hs.user_id = c.user_id and hs.hotel_id = c.hotel_id
                where c.hotel_id = ? and c.enabled
                on conflict (consent_id, event_key) do nothing
                """, event, template, contents, messageId, offerId, round, Timestamp.from(deadline), hotelId);
    }

    @Scheduled(fixedDelayString = "PT2S")
    void deliver() {
        if (!enabled()) return;
        transactions.executeWithoutResult(transaction -> {
            var jobs = jdbc.query("""
                    select q.id, q.event_key, q.template_name, q.content, q.attempts, u.phone,
                           (c.enabled and q.send_before > now() and
                           (q.ticket_booking_id is null or exists (
                              select 1 from bookings b where b.id = q.ticket_booking_id
                                and b.guest_id = u.id and b.status = 'CONFIRMED'
                           )) and
                           (q.offer_id is null or exists (
                              select 1 from bid_offers o join bid_requests b on b.id = o.bid_request_id
                              where o.id = q.offer_id and o.round = q.round and b.status = 'OPEN'
                                and b.expires_at > now()
                                and ((c.hotel_id is null and o.status in ('ACCEPTED', 'COUNTERED'))
                                  or (c.hotel_id is not null and o.status = 'PENDING'))
                           )) and
                           ((c.hotel_id is null and u.role = 'GUEST') or (u.role = 'HOTEL_STAFF' and exists
                           (select 1 from hotel_staff h where h.user_id = c.user_id and h.hotel_id = c.hotel_id)))) as allowed
                    from whatsapp_outbox q join whatsapp_consents c on c.id = q.consent_id join users u on u.id = c.user_id
                    where q.state = 'QUEUED' and q.next_attempt_at <= now()
                    order by q.next_attempt_at, q.id limit 1 for update of q skip locked
                    """, (rs, row) -> new Job(rs.getLong("id"), rs.getString("event_key"), rs.getString("template_name"),
                    rs.getString("content"), rs.getInt("attempts"), rs.getString("phone"), rs.getBoolean("allowed")));
            if (jobs.isEmpty()) return;
            deliver(jobs.getFirst());
        });
    }

    private void deliver(Job job) {
        if (!job.allowed() || !job.phone().matches("[6-9][0-9]{9}")) {
            jdbc.update("update whatsapp_outbox set state = 'SKIPPED' where id = ?", job.id());
            return;
        }
        try {
            String number = "91" + job.phone();
            String id = job.template() == null
                    ? client.sendReply(number, job.event(), job.body())
                    : client.sendTemplate(number, job.template(), json.readValue(job.body(), new TypeReference<List<String>>() {}));
            jdbc.update("update whatsapp_outbox set state = 'SUBMITTED', provider_message_id = ? where id = ?", id, job.id());
        } catch (JsonProcessingException malformed) {
            jdbc.update("update whatsapp_outbox set state = 'FAILED' where id = ?", job.id());
        } catch (RuntimeException failure) {
            int attempts = job.attempts() + 1;
            boolean permanent = failure instanceof RestClientResponseException response
                    && response.getStatusCode().is4xxClientError() && response.getStatusCode().value() != 429;
            jdbc.update("""
                    update whatsapp_outbox set attempts = ?, state = ?,
                       next_attempt_at = now() + make_interval(secs => ?) where id = ?
                    """, attempts, permanent || attempts >= 8 ? "FAILED" : "QUEUED", Math.min(1800, 5 * (1 << attempts)), job.id());
        }
    }

    public Map<Long, String> deliveryStatus(List<Long> messageIds) {
        if (messageIds.isEmpty()) return Map.of();
        // Report the furthest stage reached by any consenting recipient for that partner message.
        String placeholders = String.join(",", java.util.Collections.nCopies(messageIds.size(), "?"));
        return jdbc.query("""
                select distinct on (q.partner_message_id) q.partner_message_id,
                  coalesce(d.status, case q.state when 'FAILED' then 'failed' when 'SKIPPED' then 'skipped'
                    when 'SUBMITTED' then 'submitted' else 'queued' end)
                from whatsapp_outbox q left join whatsapp_delivery d on d.provider_message_id = q.provider_message_id
                where q.partner_message_id in (
                """ + placeholders + ") order by q.partner_message_id, coalesce(d.priority, 0) desc, q.id",
                (rs, row) -> Map.entry(rs.getLong(1), Objects.requireNonNull(rs.getString(2))), messageIds.toArray())
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private String encode(List<String> body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Job(long id, String event, String template, String body, int attempts, String phone, boolean allowed) {}
}
