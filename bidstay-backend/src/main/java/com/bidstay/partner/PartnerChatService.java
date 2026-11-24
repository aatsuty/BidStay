package com.bidstay.partner;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists the partner inbox and its linked WhatsApp conversation. */
@Service
@RequiredArgsConstructor
public class PartnerChatService {

    private final PartnerMessageRepository repo;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    @Transactional
    public PartnerMessage bot(Long hotelId, PartnerMessage.Kind kind, String body, Long offerId) {
        return save(hotelId, PartnerMessage.Sender.BOT, kind, body, offerId);
    }

    @Transactional
    public PartnerMessage hotelSays(Long hotelId, String body) {
        return save(hotelId, PartnerMessage.Sender.HOTEL, PartnerMessage.Kind.REPLY, body, null);
    }

    public void whatsappSays(long hotelId, long offerId, String messageId, String body) {
        jdbc.update("""
                insert into partner_messages (hotel_id, sender, kind, body, bid_offer_id, created_at, channel, provider_message_id)
                values (?, 'HOTEL', 'REPLY', ?, ?, now(), 'WHATSAPP', ?)
                on conflict (provider_message_id) do nothing
                """, hotelId, body, offerId, messageId);
    }

    /** afterId = 0 returns the latest 100 messages, oldest first. Otherwise only newer messages. */
    @Transactional(readOnly = true)
    public List<PartnerMessage> history(Long hotelId, long afterId) {
        if (afterId > 0) return repo.findByHotelIdAndIdGreaterThanOrderByIdAsc(hotelId, afterId);
        List<PartnerMessage> latest = new ArrayList<>(repo.findTop100ByHotelIdOrderByIdDesc(hotelId));
        Collections.reverse(latest);
        return latest;
    }

    private PartnerMessage save(Long hotelId, PartnerMessage.Sender sender, PartnerMessage.Kind kind, String body, Long offerId) {
        PartnerMessage m = new PartnerMessage();
        m.setHotelId(hotelId);
        m.setSender(sender);
        m.setKind(kind);
        m.setBody(body);
        m.setBidOfferId(offerId);
        m.setCreatedAt(clock.instant());
        return repo.save(m);
    }
}
