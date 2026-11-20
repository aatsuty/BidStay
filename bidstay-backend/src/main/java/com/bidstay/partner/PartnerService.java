package com.bidstay.partner;

import com.bidstay.bidding.BidDtos.HotelOfferView;
import com.bidstay.bidding.BidDtos.HotelRespond;
import com.bidstay.bidding.BidService;
import com.bidstay.bidding.HotelAction;
import com.bidstay.common.ApiException;
import com.bidstay.hotel.*;
import com.bidstay.inventory.InventoryService;
import com.bidstay.partner.PartnerDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Everything a hotel owner can do. Access (is this user allowed for this hotel?) is checked by the controller. */
@Service
@RequiredArgsConstructor
public class PartnerService {

    private static final Pattern COUNTER = Pattern.compile("^(COUNTER|C)\\s*₹?\\s*(\\d{3,6})\\b");

    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final HotelDocumentRepository docs;
    private final InventoryService inventory;
    private final BidService bids;
    private final PartnerChatService chat;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ registration & documents

    @Transactional(readOnly = true)
    public List<MyHotel> myHotels(List<Long> hotelIds) {
        return hotels.findAllById(hotelIds).stream()
                .map(h -> new MyHotel(h.getId(), h.getName(), h.getArea(), h.getStatus(), h.getRejectionReason())).toList();
    }

    @Transactional
    public MyHotel register(Long userId, RegisterHotel cmd) {
        Hotel h = new Hotel();
        h.setName(cmd.name().trim());
        h.setCity(cmd.city().trim());
        h.setArea(cmd.area().trim());
        h.setAddress(cmd.address().trim());
        h.setStarRating(cmd.starRating());
        h.setGstNumber(cmd.gstNumber());
        h.setContactPhone(cmd.contactPhone());
        h.setStatus(HotelStatus.PENDING_VERIFICATION);
        hotels.save(h);

        LocalDate today = LocalDate.now(clock);
        for (RoomInput r : cmd.rooms()) {
            if (r.minPrice().compareTo(r.listedPrice()) > 0) throw ApiException.invalid("Minimum price cannot be above the listed price");
            RoomType rt = new RoomType();
            rt.setHotelId(h.getId());
            rt.setCategory(r.category());
            rt.setName(r.name());
            rt.setMaxGuests(r.maxGuests());
            rt.setListedPrice(r.listedPrice());
            rt.setMinPrice(r.minPrice());
            rt.setTotalRooms(r.totalRooms());
            roomTypes.save(rt);
            inventory.ensureRange(rt.getId(), today, today.plusDays(InventoryJobs.HORIZON_DAYS), r.totalRooms());
        }
        jdbc.update("insert into hotel_staff (user_id, hotel_id) values (?, ?)", userId, h.getId());
        chat.bot(h.getId(), PartnerMessage.Kind.SYSTEM,
                "*Welcome to BidStay.* Add your four documents (GST, licence, owner ID, photos) so our team can verify "
                        + h.getName() + ". Bid requests will appear in this chat once you are live.", null);
        return new MyHotel(h.getId(), h.getName(), h.getArea(), h.getStatus(), null);
    }

    @Transactional(readOnly = true)
    public List<DocumentView> documents(Long hotelId) {
        Map<DocType, HotelDocument> have = new EnumMap<>(DocType.class);
        docs.findByHotelId(hotelId).forEach(d -> have.put(d.getDocType(), d));
        return Arrays.stream(DocType.values()).map(t -> {
            HotelDocument d = have.get(t);
            return new DocumentView(t, d != null, d != null && d.isVerified(), d == null ? null : d.getReference());
        }).toList();
    }

    @Transactional
    public List<DocumentView> submitDocument(Long hotelId, DocType type, String reference) {
        HotelDocument d = docs.findByHotelIdAndDocType(hotelId, type).orElseGet(HotelDocument::new);
        d.setHotelId(hotelId);
        d.setDocType(type);
        d.setReference(reference.trim());
        d.setSubmittedAt(clock.instant());
        d.setVerified(false);          // a changed document must be checked again
        d.setVerifiedAt(null);
        docs.save(d);

        Hotel h = hotels.findById(hotelId).orElseThrow();
        if (h.getStatus() == HotelStatus.REJECTED) {      // owner fixed something: back into the review queue
            h.setStatus(HotelStatus.PENDING_VERIFICATION);
            h.setRejectionReason(null);
        }
        chat.bot(hotelId, PartnerMessage.Kind.SYSTEM, "We received your " + type.name().replace('_', ' ').toLowerCase() + ". Our team will verify it shortly.", null);
        return documents(hotelId);
    }

    // ------------------------------------------------------------------ rates & rules

    @Transactional(readOnly = true)
    public Rates rates(Long hotelId) {
        Hotel h = hotels.findById(hotelId).orElseThrow(() -> ApiException.notFound("Hotel"));
        List<RoomRate> rooms = roomTypes.findByHotelIdOrderById(hotelId).stream()
                .map(r -> new RoomRate(r.getId(), r.getCategory(), r.getName(), r.getListedPrice(), r.getMinPrice(), r.getTotalRooms())).toList();
        return new Rates(h.isAutoMode(), h.isAutoCounter(), h.getAutoCounterPct(), h.isAutoDecline(), rooms);
    }

    @Transactional
    public Rates updateRates(Long hotelId, RatesUpdate cmd) {
        Hotel h = hotels.findById(hotelId).orElseThrow(() -> ApiException.notFound("Hotel"));
        for (RoomRateUpdate u : cmd.rooms()) {
            RoomType rt = ownedRoom(hotelId, u.roomTypeId());
            if (u.minPrice().compareTo(u.listedPrice()) > 0) throw ApiException.invalid(rt.getName() + ": minimum price cannot be above the listed price");
            rt.setListedPrice(u.listedPrice());
            rt.setMinPrice(u.minPrice());
        }
        h.setAutoMode(cmd.autoMode());
        h.setAutoCounter(cmd.autoMode() && cmd.autoCounter());
        h.setAutoCounterPct(cmd.autoCounterPct());
        h.setAutoDecline(cmd.autoMode() && cmd.autoDecline());
        return rates(hotelId);
    }

    // ------------------------------------------------------------------ availability

    @Transactional(readOnly = true)
    public List<RoomAvailability> availability(Long hotelId, int days) {
        LocalDate from = LocalDate.now(clock);
        int span = Math.max(1, Math.min(days, 60));
        return roomTypes.findByHotelIdOrderById(hotelId).stream().map(rt -> new RoomAvailability(rt.getId(), rt.getName(),
                inventory.view(rt.getId(), from, from.plusDays(span)).stream()
                        .map(i -> new NightView(i.getStayDate(), i.getTotalRooms(), i.getHeldRooms(), i.getSoldRooms())).toList())).toList();
    }

    @Transactional
    public void setAvailability(Long hotelId, AvailabilityUpdate cmd) {
        ownedRoom(hotelId, cmd.roomTypeId());
        LocalDate today = LocalDate.now(clock);
        if (cmd.from().isBefore(today) || !cmd.to().isAfter(cmd.from())) throw ApiException.invalid("Choose a future date range (end after start)");
        if (cmd.to().isAfter(today.plusDays(InventoryJobs.HORIZON_DAYS))) throw ApiException.invalid("You can edit up to " + InventoryJobs.HORIZON_DAYS + " days ahead");
        inventory.setTotal(cmd.roomTypeId(), cmd.from(), cmd.to(), cmd.totalRooms());
    }

    // ------------------------------------------------------------------ bid requests

    public List<HotelOfferView> pendingOffers(Long hotelId) {
        return bids.pendingForHotel(hotelId);
    }

    public Map<String, Object> respond(Long hotelId, Long offerId, HotelRespond cmd) {
        return Map.of("status", bids.respond(hotelId, offerId, cmd));
    }

    // ------------------------------------------------------------------ WhatsApp-style chat

    public List<ChatMessageView> chatHistory(Long hotelId, long afterId) {
        return chat.history(hotelId, afterId).stream().map(PartnerService::view).toList();
    }

    /**
     * Understands the same replies as the demo: YES, NO, COUNTER 1500.
     * Deliberately NOT @Transactional: a rejected reply (for example an expired request) must not roll
     * back the chat messages that explain why.
     */
    public List<ChatMessageView> handleChatText(Long hotelId, String raw) {
        String text = raw.trim();
        PartnerMessage sent = chat.hotelSays(hotelId, text);
        String u = text.toUpperCase(Locale.ROOT);

        List<HotelOfferView> pending = bids.pendingForHotel(hotelId);
        Matcher counter = COUNTER.matcher(u);
        HotelAction action = null;
        BigDecimal counterPrice = null;
        if (u.matches("^(YES|Y|ACCEPT)\\b.*")) action = HotelAction.ACCEPT;
        else if (u.matches("^(NO|N|DECLINE)\\b.*")) action = HotelAction.DECLINE;
        else if (counter.find()) {
            action = HotelAction.COUNTER;
            counterPrice = new BigDecimal(counter.group(2));
        }

        if (action == null) {
            chat.bot(hotelId, PartnerMessage.Kind.SYSTEM,
                    "Sorry, I did not understand that. Reply *YES* to accept, *NO* to decline, or *COUNTER 1500* with your price.", null);
        } else if (pending.isEmpty()) {
            chat.bot(hotelId, PartnerMessage.Kind.SYSTEM, "There is no bid waiting for your reply right now.", null);
        } else {
            HotelOfferView target = pending.get(0);        // newest request first
            try {
                bids.respond(hotelId, target.offerId(), new HotelRespond(action, counterPrice));
                if (pending.size() > 1) {
                    chat.bot(hotelId, PartnerMessage.Kind.SYSTEM,
                            "You have " + (pending.size() - 1) + " more request(s) waiting. Open the Requests page to reply to a specific one.", null);
                }
            } catch (ApiException e) {
                chat.bot(hotelId, PartnerMessage.Kind.SYSTEM, "Could not record that: " + e.getMessage(), target.offerId());
            }
        }
        return chatHistory(hotelId, sent.getId() - 1);
    }

    // ------------------------------------------------------------------ bookings & insights

    @Transactional(readOnly = true)
    public List<BookingRow> bookings(Long hotelId) {
        return jdbc.query("""
                select b.id, b.status, rt.name, b.check_in, b.check_out, b.rooms, b.price_per_night,
                       b.total_amount, b.commission_amount, u.name, u.phone
                from bookings b
                join room_types rt on rt.id = b.room_type_id
                join users u on u.id = b.guest_id
                where b.hotel_id = ? and b.status = 'CONFIRMED'
                order by b.check_in desc limit 100
                """, (rs, i) -> new BookingRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getDate(4).toLocalDate(), rs.getDate(5).toLocalDate(), rs.getInt(6), rs.getBigDecimal(7),
                rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(8).subtract(rs.getBigDecimal(9)),
                rs.getString(10), rs.getString(11)), hotelId);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(Long hotelId) {
        String since = "created_at >= now() - interval '30 days'";
        long pending = count("select count(*) from bid_offers where hotel_id = ? and status = 'PENDING' and expires_at > now()", hotelId);
        long bidsReceived = count("select count(*) from bid_offers where hotel_id = ? and status <> 'UNAVAILABLE' and " + since, hotelId);
        long accepted = count("select count(*) from bid_offers where hotel_id = ? and status in ('ACCEPTED','SELECTED') and " + since, hotelId);
        long countered = count("select count(*) from bid_offers where hotel_id = ? and status = 'COUNTERED' and " + since, hotelId);
        long lost = count("select count(*) from bid_offers where hotel_id = ? and status in ('DECLINED','EXPIRED') and " + since, hotelId);
        Double avg = jdbc.queryForObject("select avg(extract(epoch from responded_at - created_at)) from bid_offers "
                + "where hotel_id = ? and responded_at is not null and auto_accepted = false and " + since, Double.class, hotelId);
        long confirmed = count("select count(*) from bookings where hotel_id = ? and status = 'CONFIRMED' and " + since, hotelId);
        BigDecimal gross = jdbc.queryForObject("select coalesce(sum(total_amount),0) from bookings where hotel_id = ? and status = 'CONFIRMED' and " + since, BigDecimal.class, hotelId);
        BigDecimal commission = jdbc.queryForObject("select coalesce(sum(commission_amount),0) from bookings where hotel_id = ? and status = 'CONFIRMED' and " + since, BigDecimal.class, hotelId);
        long upcoming = count("select count(*) from bookings where hotel_id = ? and status = 'CONFIRMED' and check_in >= current_date", hotelId);
        return new Dashboard(pending, bidsReceived, accepted, countered, lost, avg == null ? null : Math.round(avg),
                confirmed, gross.subtract(commission), commission, upcoming);
    }

    // ------------------------------------------------------------------ helpers

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private RoomType ownedRoom(Long hotelId, Long roomTypeId) {
        return roomTypes.findById(roomTypeId).filter(r -> r.getHotelId().equals(hotelId))
                .orElseThrow(() -> ApiException.notFound("Room type"));
    }

    private static ChatMessageView view(PartnerMessage m) {
        return new ChatMessageView(m.getId(), m.getSender().name(), m.getKind().name(), m.getBody(), m.getBidOfferId(), m.getCreatedAt());
    }
}
