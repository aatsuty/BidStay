package com.bidstay.whatsapp;

import com.bidstay.bidding.BidDtos.HotelRespond;
import com.bidstay.bidding.BidService;
import com.bidstay.bidding.HotelAction;
import com.bidstay.common.ApiException;
import com.bidstay.partner.PartnerChatService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
class WhatsAppInbound {
    private static final Pattern ACTION = Pattern.compile("(?:(YES|NO)|COUNTER\\s+₹?\\s*([0-9]{1,6}(?:\\.[0-9]{1,2})?))(?:\\s+([0-9]{1,18})/([0-9]{1,9}))?");
    private final JdbcTemplate jdbc;
    private final WhatsAppNotifications notifications;
    private final PartnerChatService chat;
    private final BidService bids;
    private final BidReplyAssistant assistant;
    private final Clock clock;

    void delivery(JsonNode update) {
        String status = update.path("status").asText();
        int priority = switch (status) {
            case "sent" -> 1;
            case "failed" -> 2;
            case "delivered" -> 3;
            case "read" -> 4;
            default -> 0;
        };
        String id = update.path("id").asText();
        if (priority == 0 || id.isBlank() || id.length() > 255) return;
        jdbc.update("""
                insert into whatsapp_delivery (provider_message_id, status, priority) values (?, ?, ?)
                on conflict (provider_message_id) do update
                  set status = excluded.status, priority = excluded.priority, observed_at = now()
                where whatsapp_delivery.priority < excluded.priority
                """, id, status, priority);
    }

    void message(JsonNode message) {
        String id = message.path("id").asText();
        String from = message.path("from").asText();
        String quote = message.path("context").path("id").asText();
        String text = message.path("text").path("body").asText().strip();
        if (id.isBlank() || id.length() > 255 || !from.matches("91[6-9][0-9]{9}") || text.isBlank()) return;
        if (text.length() > 500) return;
        if ("STOP".equalsIgnoreCase(text) || "UNSUBSCRIBE".equalsIgnoreCase(text)) {
            notifications.stop(from.substring(2), id);
            return;
        }
        var matcher = ACTION.matcher(text.toUpperCase(Locale.ROOT));
        boolean reference = matcher.matches() && matcher.group(3) != null;
        if (quote.isBlank() && !reference) return;
        Long offerId = reference ? Long.valueOf(matcher.group(3)) : null;
        Integer round = reference ? Integer.valueOf(matcher.group(4)) : null;

        var targets = jdbc.query("""
                select c.id, c.hotel_id, q.offer_id, q.round from whatsapp_outbox q
                join whatsapp_consents c on c.id = q.consent_id
                join users u on u.id = c.user_id and u.role = 'HOTEL_STAFF'
                join hotel_staff h on h.user_id = c.user_id and h.hotel_id = c.hotel_id
                where c.enabled and u.phone = ? and q.offer_id is not null and q.state = 'SUBMITTED'
                  and ((? <> '' and q.provider_message_id = ?) or
                      (? = '' and q.offer_id = ? and q.round = ?))
                limit 2
                """, (rs, row) -> new Target(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)),
                from.substring(2), quote, quote, quote, offerId, round);
        if (targets.size() != 1) return;
        Target target = targets.getFirst();
        jdbc.update("""
                insert into whatsapp_inbound (provider_message_id, consent_id, hotel_id, offer_id, round, body)
                values (?, ?, ?, ?, ?, ?) on conflict (provider_message_id) do nothing
                """, id, target.consent(), target.hotel(), target.offer(), target.round(), text);
    }

    @Scheduled(fixedDelayString = "PT2S")
    void process() {
        if (!notifications.enabled()) return;
        var claims = jdbc.query("""
                with next as (
                    select provider_message_id from whatsapp_inbound where processed_at is null and next_attempt_at <= now()
                    order by next_attempt_at limit 1 for update skip locked
                )
                update whatsapp_inbound m set next_attempt_at = now() + interval '90 seconds', attempts = attempts + 1
                from next where m.provider_message_id = next.provider_message_id
                returning m.provider_message_id, consent_id, hotel_id, offer_id, round, body, received_at, attempts
                """, (rs, row) -> new Inbound(rs.getString(1), new Target(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getInt(5)),
                rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getInt(8)));
        if (claims.isEmpty()) return;
        Inbound message = claims.getFirst();

        try {
            Target target = message.target();
            boolean allowed = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from whatsapp_consents c join users u on u.id = c.user_id
                    join hotel_staff h on h.user_id = c.user_id and h.hotel_id = c.hotel_id
                    where c.id = ? and c.hotel_id = ? and c.enabled and u.role = 'HOTEL_STAFF')
                    """, Boolean.class, target.consent(), target.hotel()));
            Instant replyBefore = message.receivedAt().plusSeconds(23 * 3600);
            if (allowed && replyBefore.isAfter(clock.instant())) {
                chat.whatsappSays(target.hotel(), target.offer(), message.id(), message.body());
                String reply = handle(message);
                notifications.reply(target.consent(), message.id(), reply, replyBefore);
            }
            complete(message);
        } catch (RuntimeException failure) {
            if (message.attempts() >= 5) complete(message);
        }
    }

    private String handle(Inbound message) {
        Target target = message.target();
        String reference = target.offer() + "/" + target.round();
        var action = ACTION.matcher(message.body().toUpperCase(Locale.ROOT));
        if (!action.matches()) {
            String draft = assistant.suggest(message.body());
            return draft == null
                    ? "Reply YES " + reference + ", NO " + reference + ", or COUNTER followed by your price and " + reference + "."
                    : "Suggested reply: " + draft + " " + reference + ". Send that exact reply to confirm.";
        }
        if (action.group(3) != null && !(action.group(3) + "/" + action.group(4)).equals(reference)) {
            return "The reference in your reply differs from the quoted bid. Check the original request before replying.";
        }

        HotelAction operation = action.group(1) == null ? HotelAction.COUNTER
                : "YES".equals(action.group(1)) ? HotelAction.ACCEPT : HotelAction.DECLINE;
        BigDecimal price = action.group(2) == null ? null : new BigDecimal(action.group(2));
        try {
            bids.respond(target.hotel(), target.offer(), new HotelRespond(operation, price, target.round()));
            return "Your reply to " + reference + " was recorded.";
        } catch (ApiException failure) {
            return "Could not record your reply: " + failure.getMessage();
        }
    }

    private void complete(Inbound message) {
        jdbc.update("update whatsapp_inbound set processed_at = now() where provider_message_id = ?", message.id());
    }

    private record Target(long consent, long hotel, long offer, int round) {}
    private record Inbound(String id, Target target, String body, Instant receivedAt, int attempts) {}
}
