package com.bidstay.bidding;

import com.bidstay.bidding.BidDtos.*;
import com.bidstay.whatsapp.WhatsAppNotifications;
import com.bidstay.alerts.GuestAlerts;
import com.bidstay.booking.Booking;
import com.bidstay.booking.BookingService;
import com.bidstay.booking.BookingView;
import com.bidstay.common.ApiException;
import com.bidstay.common.BidStayProperties;
import com.bidstay.common.Money;
import com.bidstay.partner.PartnerChatService;
import com.bidstay.partner.PartnerMessage;
import com.bidstay.hotel.*;
import com.bidstay.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The bidding engine. Lifecycle of one request:
 *
 *   create ──► per hotel: UNAVAILABLE | ACCEPTED (auto, bid >= min price) | PENDING (waits for hotel)
 *   PENDING ──► hotel: ACCEPTED | COUNTERED | DECLINED   or timer: EXPIRED
 *   guest ──► raise (max rounds) re-evaluates PENDING/COUNTERED/DECLINED/EXPIRED offers
 *   guest ──► accept ACCEPTED/COUNTERED offer: rooms held, booking PENDING_PAYMENT, other offers CANCELLED
 *   payment ──► BookingService.confirmPayment
 */
@Service
@RequiredArgsConstructor
public class BidService {

    private final BidRequestRepository bids;
    private final BidOfferRepository offers;
    private final RoomTypeRepository roomTypes;
    private final HotelRepository hotels;
    private final InventoryService inventory;
    private final BookingService bookingService;
    private final PartnerChatService chat;
    private final BidStayProperties props;
    private final Clock clock;
    private final WhatsAppNotifications whatsapp;
    private final GuestAlerts alerts;

    // ------------------------------------------------------------------ guest side

    @Transactional
    public BidView create(Long guestId, CreateBid cmd) {
        Instant now = clock.instant();
        long nights = ChronoUnit.DAYS.between(cmd.checkIn(), cmd.checkOut());
        if (cmd.checkIn().isBefore(LocalDate.now(clock))) throw ApiException.invalid("Check-in cannot be in the past");
        if (nights < 1 || nights > props.maxNights()) throw ApiException.invalid("Stay must be 1 to " + props.maxNights() + " nights");

        List<Long> hotelIds = cmd.hotelIds().stream().distinct().toList();
        if (hotelIds.size() > props.maxHotelsPerBid()) throw ApiException.invalid("Too many hotels selected");

        BidRequest bid = new BidRequest();
        bid.setGuestId(guestId);
        bid.setCity(cmd.city());
        bid.setCheckIn(cmd.checkIn());
        bid.setCheckOut(cmd.checkOut());
        bid.setRooms(cmd.rooms());
        bid.setGuests(cmd.guests());
        bid.setCategory(cmd.category());
        bid.setPricePerNight(cmd.pricePerNight());
        bid.setRound(1);
        bid.setStatus(BidStatus.OPEN);
        bid.setExpiresAt(now.plus(props.bidLifetime()));
        bid.setCreatedAt(now);
        bids.save(bid);

        Map<Long, Hotel> activeHotels = hotels.findAllById(hotelIds).stream()
                .filter(hotel -> hotel.getStatus() == HotelStatus.ACTIVE)
                .collect(Collectors.toMap(Hotel::getId, Function.identity()));
        Map<Long, RoomType> eligibleRooms = roomTypes.findByHotelIdInAndCategory(activeHotels.keySet(), bid.getCategory()).stream()
                .filter(room -> room.getMaxGuests() * bid.getRooms() >= bid.getGuests())
                .collect(Collectors.toMap(RoomType::getHotelId, Function.identity()));

        int created = 0;
        for (Long hotelId : hotelIds) {
            RoomType room = eligibleRooms.get(hotelId);
            if (room == null) continue;

            BidOffer offer = new BidOffer();
            offer.setBidRequestId(bid.getId());
            offer.setHotelId(hotelId);
            offer.setRoomTypeId(room.getId());
            offer.setCreatedAt(now);
            evaluate(bid, offer, activeHotels.get(hotelId), room, now);
            offers.save(offer);
            announce(bid, offer, room);
            created++;
        }
        if (created == 0) {
            throw ApiException.invalid("None of the selected hotels can host this request");   // rolls back
        }
        return view(bid);
    }

    @Transactional
    public BidView raise(Long guestId, Long bidId, BigDecimal newPrice) {
        Instant now = clock.instant();
        BidRequest bid = loadOpenOwned(guestId, bidId, now);
        if (bid.getRound() >= props.maxRounds()) throw ApiException.invalid("Maximum number of raises reached");
        if (newPrice.compareTo(bid.getPricePerNight()) <= 0) throw ApiException.invalid("New price must be higher than your current bid");

        bid.setPricePerNight(newPrice);
        bid.setRound(bid.getRound() + 1);
        bid.setExpiresAt(now.plus(props.bidLifetime()));

        for (BidOffer o : offers.findByBidRequestId(bidId)) {
            // Offers already ACCEPTED keep their (lower) agreed price. That is simply better for the guest.
            if (OfferStatus.REOPENABLE.contains(o.getStatus())) {
                RoomType rt = roomTypes.findById(o.getRoomTypeId()).orElseThrow();
                Hotel hotel = hotels.findById(o.getHotelId()).orElseThrow();
                evaluate(bid, o, hotel, rt, now);
                announce(bid, o, rt);
            }
        }
        return view(bid);
    }

    @Transactional
    public BookingView acceptOffer(Long guestId, Long bidId, Long offerId) {
        BidRequest bid = loadOpenOwned(guestId, bidId, clock.instant());
        BidOffer chosen = offers.findById(offerId)
                .filter(o -> o.getBidRequestId().equals(bidId))
                .orElseThrow(() -> ApiException.notFound("Offer"));
        if (!OfferStatus.BOOKABLE.contains(chosen.getStatus())) {
            throw ApiException.conflict("This offer cannot be accepted (status " + chosen.getStatus() + ")");
        }

        requireActiveHotel(chosen.getHotelId());
        Booking booking = bookingService.startPayment(bid, chosen);   // throws 409 if rooms were just taken

        chosen.setStatus(OfferStatus.SELECTED);
        for (BidOffer other : offers.findByBidRequestIdAndStatusIn(bidId, OfferStatus.LIVE)) {
            if (!other.getId().equals(chosen.getId())) {
                if (other.getStatus() == OfferStatus.PENDING) {
                    chat.bot(other.getHotelId(), PartnerMessage.Kind.SYSTEM,
                            "The guest chose another offer, so this bid request is closed. No reply needed.", other.getId());
                }
                other.setStatus(OfferStatus.CANCELLED);
            }
        }
        bid.setStatus(BidStatus.HELD);
        return bookingService.toView(booking);
    }

    @Transactional(readOnly = true)
    public BidView get(Long guestId, Long bidId) {
        BidRequest bid = bids.findById(bidId).filter(b -> b.getGuestId().equals(guestId))
                .orElseThrow(() -> ApiException.notFound("Bid"));
        return view(bid);
    }

    @Transactional(readOnly = true)
    public List<BidView> listForGuest(Long guestId) {
        return bids.findByGuestIdOrderByCreatedAtDesc(guestId).stream().map(this::view).toList();
    }

    // ------------------------------------------------------------------ hotel side

    @Transactional(readOnly = true)
    public List<HotelOfferView> pendingForHotel(Long hotelId) {
        Instant now = clock.instant();
        List<HotelOfferView> out = new ArrayList<>();
        for (BidOffer o : offers.findByHotelIdAndStatusOrderByCreatedAtDesc(hotelId, OfferStatus.PENDING)) {
            if (o.getExpiresAt() == null || !o.getExpiresAt().isAfter(now)) continue;
            BidRequest bid = bids.findById(o.getBidRequestId()).orElseThrow();
            RoomType rt = roomTypes.findById(o.getRoomTypeId()).orElseThrow();
            out.add(new HotelOfferView(o.getId(), rt.getName(), bid.getPricePerNight(), rt.getListedPrice(), rt.getMinPrice(),
                    bid.getCheckIn(), bid.getCheckOut(), bid.getRooms(), bid.nights(), o.getRound(), o.getExpiresAt()));
        }
        return out;
    }

    @Transactional
    public OfferStatus respond(Long hotelId, Long offerId, HotelRespond cmd) {
        Instant now = clock.instant();
        BidOffer offer = offers.findByIdAndHotelId(offerId, hotelId).orElseThrow(() -> ApiException.notFound("Offer"));
        if (!Objects.equals(cmd.round(), offer.getRound())) {
            throw ApiException.conflict("This bid has changed. Refresh the request before replying.");
        }
        requireActiveHotel(hotelId);
        if (offer.getStatus() != OfferStatus.PENDING || offer.getExpiresAt() == null || !offer.getExpiresAt().isAfter(now)) {
            throw ApiException.conflict("This request is no longer open for a reply");
        }
        BidRequest bid = bids.findById(offer.getBidRequestId()).orElseThrow();
        if (bid.getStatus() != BidStatus.OPEN || !bid.getExpiresAt().isAfter(now)) throw ApiException.conflict("The guest has already closed this request");

        switch (cmd.action()) {
            case ACCEPT -> {
                RoomType room = roomTypes.findById(offer.getRoomTypeId()).orElseThrow();
                if (bid.getPricePerNight().compareTo(room.getMinPrice()) < 0) {
                    throw ApiException.invalid("This bid is below your minimum price");
                }
                offer.setStatus(OfferStatus.ACCEPTED);
                offer.setOfferedPrice(bid.getPricePerNight());
            }
            case COUNTER -> {
                RoomType rt = roomTypes.findById(offer.getRoomTypeId()).orElseThrow();
                BigDecimal c = cmd.counterPrice();
                if (c == null || c.compareTo(bid.getPricePerNight()) <= 0) throw ApiException.invalid("Counter must be higher than the guest's bid");
                if (c.compareTo(rt.getMinPrice()) < 0) throw ApiException.invalid("Counter cannot be below your minimum price");
                if (c.compareTo(rt.getListedPrice()) > 0) throw ApiException.invalid("Counter cannot exceed the listed price");
                offer.setStatus(OfferStatus.COUNTERED);
                offer.setOfferedPrice(c);
            }
            case DECLINE -> offer.setStatus(OfferStatus.DECLINED);
        }
        offer.setRespondedAt(now);
        offer.setExpiresAt(null);
        String what = switch (cmd.action()) {
            case ACCEPT -> "accepted " + Money.inr(bid.getPricePerNight()) + " per night";
            case COUNTER -> "countered with " + Money.inr(offer.getOfferedPrice()) + " per night";
            case DECLINE -> "declined this bid";
        };
        chat.bot(hotelId, PartnerMessage.Kind.SYSTEM, "Recorded: you " + what + ".", offer.getId());
        if (OfferStatus.BOOKABLE.contains(offer.getStatus())) {
            notifyGuestOffer(bid, offer);
        }
        return offer.getStatus();
    }

    // ------------------------------------------------------------------ scheduler hooks

    /** Hotels that did not reply within the window: PENDING -> EXPIRED. */
    @Transactional
    public int expireOffers() {
        List<BidOffer> late = offers.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAscIdAsc(OfferStatus.PENDING, clock.instant());
        late.forEach(o -> o.setStatus(OfferStatus.EXPIRED));
        return late.size();
    }

    /** Guest never chose anything within the bid lifetime: close the bid and its live offers. */
    @Transactional
    public int expireBids() {
        List<BidRequest> stale = bids.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAscIdAsc(BidStatus.OPEN, clock.instant());
        for (BidRequest bid : stale) {
            bid.setStatus(BidStatus.EXPIRED);
            offers.findByBidRequestIdAndStatusIn(bid.getId(), OfferStatus.LIVE)
                    .forEach(o -> o.setStatus(OfferStatus.EXPIRED));
        }
        return stale.size();
    }

    // ------------------------------------------------------------------ internals

    /**
     * The one place that decides what a hotel's offer looks like for the current bid price.
     * Used on creation and on every raise, so the rules cannot drift apart.
     *
     *  - no rooms                         -> UNAVAILABLE
     *  - auto mode and bid >= minimum     -> ACCEPTED (automatic)
     *  - auto mode + auto-counter and the bid is within X% below the minimum -> COUNTERED at the minimum (automatic)
     *  - auto mode + auto-decline         -> DECLINED (automatic)
     *  - otherwise (manual mode, or nothing above matched) -> PENDING, the owner replies
     * "autoAccepted" means "decided by the system", whatever the outcome.
     */
    private void evaluate(BidRequest bid, BidOffer o, Hotel hotel, RoomType rt, Instant now) {
        BigDecimal price = bid.getPricePerNight();
        o.setRound(bid.getRound());
        o.setAutoAccepted(false);
        o.setRespondedAt(null);
        o.setOfferedPrice(null);
        o.setExpiresAt(null);

        if (hotel.getStatus() != HotelStatus.ACTIVE || !inventory.isAvailable(rt.getId(), bid.getCheckIn(), bid.getCheckOut(), bid.getRooms())) {
            o.setStatus(OfferStatus.UNAVAILABLE);
            return;
        }
        if (hotel.isAutoMode()) {
            if (price.compareTo(rt.getMinPrice()) >= 0) {
                o.setStatus(OfferStatus.ACCEPTED);
                o.setOfferedPrice(price);
                markAuto(o, now);
                return;
            }
            if (hotel.isAutoCounter()) {
                BigDecimal floor = rt.getMinPrice()
                        .multiply(BigDecimal.valueOf(100 - hotel.getAutoCounterPct()))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                if (price.compareTo(floor) >= 0) {
                    o.setStatus(OfferStatus.COUNTERED);
                    o.setOfferedPrice(Money.roundUpTo50(rt.getMinPrice()).min(rt.getListedPrice()));
                    markAuto(o, now);
                    return;
                }
            }
            if (hotel.isAutoDecline()) {
                o.setStatus(OfferStatus.DECLINED);
                markAuto(o, now);
                return;
            }
        }
        o.setStatus(OfferStatus.PENDING);
        o.setExpiresAt(now.plus(props.offerResponseWindow()));
    }

    private void markAuto(BidOffer o, Instant now) {
        o.setAutoAccepted(true);
        o.setRespondedAt(now);
    }

    /** Puts the right message into the hotel's WhatsApp-style chat for what just happened. */
    private void announce(BidRequest bid, BidOffer o, RoomType rt) {
        Long hotelId = o.getHotelId();
        long nights = bid.nights();
        switch (o.getStatus()) {
            case PENDING -> {
                BigDecimal suggest = Money.roundUpTo50(
                        bid.getPricePerNight().add(rt.getListedPrice()).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP));
                BigDecimal below = BigDecimal.ONE.subtract(bid.getPricePerNight().divide(rt.getListedPrice(), 4, RoundingMode.HALF_UP))
                        .multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
                String reference = o.getId() + "/" + o.getRound();
                var message = chat.bot(hotelId, PartnerMessage.Kind.REQUEST,
                        "*New bid request* (round " + bid.getRound() + ")\n"
                                + Money.inr(bid.getPricePerNight()) + " per night for " + rt.getName() + "\n"
                                + bid.getCheckIn() + " to " + bid.getCheckOut() + " · " + nights + " night" + (nights > 1 ? "s" : "")
                                + " · " + bid.getRooms() + " room" + (bid.getRooms() > 1 ? "s" : "") + "\n"
                                + "Your listed price: " + Money.inr(rt.getListedPrice()) + " (bid is " + below + "% lower)\n"
                                + "Reply *YES " + reference + "*, *NO " + reference + "*, or *COUNTER "
                                + suggest.toBigInteger() + " " + reference + "*.\n"
                                + "Please reply within " + props.offerResponseWindow().toMinutes() + " minutes.",
                        o.getId());
                whatsapp.hotelBid(hotelId, message.getId(), o.getId(), o.getRound(), bid.getPricePerNight(),
                        bid.getCheckIn(), bid.getCheckOut(), bid.getRooms(), o.getExpiresAt());
            }
            case ACCEPTED -> {
                if (o.isAutoAccepted()) chat.bot(hotelId, PartnerMessage.Kind.SYSTEM,
                        "Auto-accepted *" + Money.inr(o.getOfferedPrice()) + "* by your minimum-price rule. The guest can book now.", o.getId());
            }
            case COUNTERED -> {
                if (o.isAutoAccepted()) chat.bot(hotelId, PartnerMessage.Kind.SYSTEM,
                        "Auto-countered with *" + Money.inr(o.getOfferedPrice()) + "* (the bid of " + Money.inr(bid.getPricePerNight())
                                + " was close to your minimum).", o.getId());
            }
            case DECLINED -> {
                if (o.isAutoAccepted()) chat.bot(hotelId, PartnerMessage.Kind.SYSTEM,
                        "Auto-declined " + Money.inr(bid.getPricePerNight()) + ", below your minimum.", o.getId());
            }
            default -> { /* UNAVAILABLE etc.: nothing to tell the hotel */ }
        }
        if (OfferStatus.BOOKABLE.contains(o.getStatus())) {
            notifyGuestOffer(bid, o);
        }
    }

    private void notifyGuestOffer(BidRequest bid, BidOffer offer) {
        String hotel = hotels.findById(offer.getHotelId()).orElseThrow().getName();
        alerts.offer(bid.getGuestId(), bid.getId(), offer.getId(), offer.getRound(), hotel, offer.getOfferedPrice());
        whatsapp.guestOffer(bid.getGuestId(), bid.getId(), offer.getId(), offer.getRound(), offer.getOfferedPrice(), bid.getExpiresAt());
    }

    private void requireActiveHotel(Long hotelId) {
        if (hotels.findById(hotelId).orElseThrow().getStatus() != HotelStatus.ACTIVE) {
            throw ApiException.conflict("This hotel is no longer available for bidding");
        }
    }

    private BidRequest loadOpenOwned(Long guestId, Long bidId, Instant now) {
        BidRequest bid = bids.findById(bidId).filter(b -> b.getGuestId().equals(guestId))
                .orElseThrow(() -> ApiException.notFound("Bid"));
        if (bid.getStatus() != BidStatus.OPEN || !bid.getExpiresAt().isAfter(now)) {
            throw ApiException.conflict("This bid is closed");
        }
        return bid;
    }

    private BidView view(BidRequest bid) {
        List<BidOffer> os = offers.findByBidRequestId(bid.getId());
        Map<Long, Hotel> byId = hotels.findAllById(os.stream().map(BidOffer::getHotelId).toList()).stream()
                .collect(Collectors.toMap(Hotel::getId, Function.identity()));

        List<OfferView> views = os.stream()
                .sorted(Comparator
                        .comparing((BidOffer o) -> OfferStatus.BOOKABLE.contains(o.getStatus()) ? 0 : 1)
                        .thenComparing(BidOffer::getOfferedPrice, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(o -> {
                    Hotel h = byId.get(o.getHotelId());
                    BigDecimal total = o.getOfferedPrice() == null ? null
                            : o.getOfferedPrice().multiply(BigDecimal.valueOf(bid.nights() * bid.getRooms()));
                    return new OfferView(o.getId(), h.getId(), h.getName(), h.getArea(), o.getStatus(),
                            o.getOfferedPrice(), total, o.isAutoAccepted(), o.getExpiresAt());
                }).toList();

        return new BidView(bid.getId(), bid.getStatus(), bid.getRound(), props.maxRounds(), bid.getPricePerNight(),
                bid.getCheckIn(), bid.getCheckOut(), bid.getRooms(), bid.getExpiresAt(),
                bookingService.latestBookingIdForBid(bid.getId()), bookingService.cancellationDeadline(bid.getCheckIn()), views);
    }
}
