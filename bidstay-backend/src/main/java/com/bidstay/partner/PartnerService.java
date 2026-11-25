package com.bidstay.partner;

import com.bidstay.audit.AuditTrail;
import com.bidstay.bidding.BidDtos.HotelOfferView;
import com.bidstay.bidding.BidDtos.HotelRespond;
import com.bidstay.whatsapp.WhatsAppNotifications;
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
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Everything a hotel owner can do. Access (is this user allowed for this hotel?) is checked by the controller. */
@Service
@RequiredArgsConstructor
public class PartnerService {

    private static final Pattern CHAT_REPLY = Pattern.compile(
            "^(?:(YES|NO)|COUNTER\\s+₹?\\s*(\\d{1,9}(?:\\.\\d{1,2})?))\\s+(\\d{1,18})/(\\d{1,9})$");

    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final HotelDocumentRepository docs;
    private final InventoryService inventory;
    private final BidService bids;
    private final PartnerChatService chat;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final WhatsAppNotifications whatsapp;
    private final AuditTrail audit;

    // ------------------------------------------------------------------ registration & documents

    @Transactional(readOnly = true)
    public List<MyHotel> myHotels(List<Long> hotelIds) {
        return hotels.findAllById(hotelIds).stream()
                .sorted(Comparator.comparing(Hotel::getId))
                .map(h -> new MyHotel(h.getId(), h.getName(), h.getArea(), h.getStatus(), h.getRejectionReason())).toList();
    }

    @Transactional
    public MyHotel register(Long userId, RegisterHotel cmd) {
        MapSearchFilter.validateCoordinates(cmd.latitude(), cmd.longitude());
        Hotel h = new Hotel();
        h.setName(cmd.name().trim());
        h.setCity(cmd.city().trim());
        h.setArea(cmd.area().trim());
        h.setAddress(cmd.address().trim());
        h.setLatitude(cmd.latitude());
        h.setLongitude(cmd.longitude());
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
        jdbc.update("insert into hotel_staff (user_id, hotel_id, can_manage_staff) values (?, ?, true)", userId, h.getId());
        chat.bot(h.getId(), PartnerMessage.Kind.SYSTEM,
                "*Welcome to BidStay.* Add your four documents (GST, licence, owner ID, photos) so our team can verify "
                        + h.getName() + ". Bid requests will appear in this chat once you are live.", null);
        return new MyHotel(h.getId(), h.getName(), h.getArea(), h.getStatus(), null);
    }

    @Transactional(readOnly = true)
    public List<String> locationNames(String prefix, String city) {
        if (prefix.length() > 80 || city.length() > 80) throw ApiException.invalid("Location name is too long");
        String term = prefix.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        if (city.isBlank()) {
            return jdbc.queryForList("""
                    select name from location_cities where name_key like lower(?) escape '!'
                    order by name_key limit 8
                    """, String.class, term);
        }
        return jdbc.queryForList("""
                select distinct name from location_neighbourhoods
                where city_id in (select destination_cities(?)) and name_key like lower(?) escape '!'
                order by name limit 8
                """, String.class, city, term);
    }

    @Transactional(readOnly = true)
    public MapPin mapPin(Long hotelId) {
        Hotel hotel = hotels.findById(hotelId).orElseThrow(() -> ApiException.notFound("Hotel"));
        return new MapPin(hotel.getLatitude(), hotel.getLongitude());
    }

    @Transactional
    public MapPin updateMapPin(Long actor, Long hotelId, MapPin pin) {
        MapSearchFilter.validateCoordinates(pin.latitude(), pin.longitude());
        Hotel hotel = hotels.findById(hotelId).orElseThrow(() -> ApiException.notFound("Hotel"));
        hotel.setLatitude(pin.latitude());
        hotel.setLongitude(pin.longitude());
        audit.record(actor, AuditTrail.Action.HOTEL_MAP_PIN_UPDATED, hotelId, hotelId);
        return pin;
    }

    @Transactional(readOnly = true)
    public List<DocumentView> documents(Long hotelId) {
        Map<DocType, HotelDocument> have = new EnumMap<>(DocType.class);
        docs.findByHotelId(hotelId).forEach(d -> have.put(d.getDocType(), d));
        return Arrays.stream(DocType.values()).map(t -> {
            HotelDocument d = have.get(t);
            return new DocumentView(t, d != null, d != null && d.isVerified(), d == null ? null : d.getReference(), d == null ? null : d.getVersionId());
        }).toList();
    }

    @Transactional
    public List<DocumentView> submitDocument(Long hotelId, DocType type, String reference) {
        HotelDocument d = docs.findByHotelIdAndDocType(hotelId, type).orElseGet(HotelDocument::new);
        d.setHotelId(hotelId);
        d.setDocType(type);
        if (d.getVersionId() != null) throw ApiException.conflict("Upload a replacement file for this document");
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

    @Transactional(readOnly = true)
    public StayPolicy stayPolicy(Long hotelId) {
        return jdbc.query("""
                select p.check_in_from, p.check_out_by, p.guest_instructions
                from hotels h left join hotel_stay_policies p on p.hotel_id = h.id
                where h.id = ?
                """, (rs, row) -> new StayPolicy(rs.getObject(1, LocalTime.class),
                rs.getObject(2, LocalTime.class), rs.getString(3)), hotelId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Hotel"));
    }

    @Transactional
    public StayPolicy updateStayPolicy(Long actorId, Long hotelId, StayPolicy policy) {
        if (!hotels.existsById(hotelId)) throw ApiException.notFound("Hotel");
        String instructions = policy.guestInstructions() == null ? null : policy.guestInstructions().strip();
        StayPolicy updated = new StayPolicy(policy.checkInFrom(), policy.checkOutBy(),
                instructions == null || instructions.isEmpty() ? null : instructions);
        int changed = jdbc.update("""
                insert into hotel_stay_policies (hotel_id, check_in_from, check_out_by, guest_instructions)
                values (?, ?, ?, ?)
                on conflict (hotel_id) do update set check_in_from = excluded.check_in_from,
                    check_out_by = excluded.check_out_by, guest_instructions = excluded.guest_instructions
                where (hotel_stay_policies.check_in_from, hotel_stay_policies.check_out_by, hotel_stay_policies.guest_instructions)
                    is distinct from (excluded.check_in_from, excluded.check_out_by, excluded.guest_instructions)
                """, hotelId, updated.checkInFrom(), updated.checkOutBy(), updated.guestInstructions());
        if (changed != 0) audit.record(actorId, AuditTrail.Action.HOTEL_STAY_POLICY_UPDATED, hotelId, hotelId);
        return updated;
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
        var messages = chat.history(hotelId, afterId);
        var statuses = whatsapp.deliveryStatus(messages.stream().map(PartnerMessage::getId).toList());
        return messages.stream().map(m -> new ChatMessageView(m.getId(), m.getSender().name(), m.getKind().name(),
                m.getBody(), m.getBidOfferId(), m.getCreatedAt(), m.getChannel(), statuses.get(m.getId()))).toList();
    }

    /**
     * Records the message even if its referenced bid can no longer be answered.
     */
    public List<ChatMessageView> handleChatText(Long hotelId, String raw) {
        String text = raw.trim();
        PartnerMessage sent = chat.hotelSays(hotelId, text);
        Matcher reply = CHAT_REPLY.matcher(text.toUpperCase(Locale.ROOT));

        if (!reply.matches()) {
            chat.bot(hotelId, PartnerMessage.Kind.SYSTEM,
                    "Use the reply buttons or include the request and round shown in the message, for example *YES 42/1* or *COUNTER 1500 42/1*.", null);
        } else {
            Long offerId = Long.valueOf(reply.group(3));
            int round = Integer.parseInt(reply.group(4));
            HotelAction action = reply.group(1) == null ? HotelAction.COUNTER
                    : "YES".equals(reply.group(1)) ? HotelAction.ACCEPT : HotelAction.DECLINE;
            BigDecimal price = reply.group(2) == null ? null : new BigDecimal(reply.group(2));
            try {
                bids.respond(hotelId, offerId, new HotelRespond(action, price, round));
            } catch (ApiException e) {
                chat.bot(hotelId, PartnerMessage.Kind.SYSTEM, "Could not record that: " + e.getMessage(), null);
            }
        }
        return chatHistory(hotelId, sent.getId() - 1);
    }

    // ------------------------------------------------------------------ bookings & insights

    @Transactional(readOnly = true)
    public List<BookingRow> bookings(Long hotelId) {
        return jdbc.query("""
                select b.id, b.status, rt.name, b.check_in, b.check_out, b.rooms, b.price_per_night,
                       b.total_amount, b.commission_amount, u.name, u.phone,
                       case when b.hourly_rate is not null then b.arrival_at end,
                       case when b.hourly_rate is not null then b.departure_at end, b.hourly_rate
                from bookings b
                join room_types rt on rt.id = b.room_type_id
                join users u on u.id = b.guest_id
                where b.hotel_id = ? and b.status = 'CONFIRMED'
                order by b.check_in desc limit 100
                """, (rs, i) -> new BookingRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getDate(4).toLocalDate(), rs.getDate(5).toLocalDate(), rs.getInt(6), rs.getBigDecimal(7),
                rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(8).subtract(rs.getBigDecimal(9)),
                rs.getString(10), rs.getString(11),
                rs.getObject(12, java.time.OffsetDateTime.class) == null ? null : rs.getTimestamp(12).toInstant(),
                rs.getObject(13, java.time.OffsetDateTime.class) == null ? null : rs.getTimestamp(13).toInstant(),
                rs.getBigDecimal(14)), hotelId);
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


}
