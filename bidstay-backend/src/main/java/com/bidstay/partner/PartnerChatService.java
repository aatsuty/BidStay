package com.bidstay.partner;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The hotel's WhatsApp conversation with BidStay. This is the single place that writes to it.
 * To go live on real WhatsApp, add a gateway call inside bot(): the stored message is sent to the
 * hotel's WhatsApp number, and the inbound webhook calls PartnerService.handleChatText().
 */
@Service
@RequiredArgsConstructor
public class PartnerChatService {

    private final PartnerMessageRepository repo;
    private final Clock clock;

    @Transactional
    public PartnerMessage bot(Long hotelId, PartnerMessage.Kind kind, String body, Long offerId) {
        return save(hotelId, PartnerMessage.Sender.BOT, kind, body, offerId);
    }

    @Transactional
    public PartnerMessage hotelSays(Long hotelId, String body) {
        return save(hotelId, PartnerMessage.Sender.HOTEL, PartnerMessage.Kind.REPLY, body, null);
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
