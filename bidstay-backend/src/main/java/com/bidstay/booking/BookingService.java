package com.bidstay.booking;

import com.bidstay.audit.AuditTrail;
import com.bidstay.audit.AuditTrail.Action;
import com.bidstay.booking.BookingController.ListedStay;
import com.bidstay.booking.BookingController.HourlyStay;
import com.bidstay.bidding.BidOffer;
import com.bidstay.bidding.BidRequest;
import com.bidstay.bidding.BidRequestRepository;
import com.bidstay.bidding.BidStatus;
import com.bidstay.common.ApiException;
import com.bidstay.common.BidStayProperties;
import com.bidstay.common.Money;
import com.bidstay.hotel.StayPolicy;
import com.bidstay.hotel.HourlyStays;
import com.bidstay.partner.PartnerChatService;
import com.bidstay.partner.PartnerMessage;
import com.bidstay.whatsapp.WhatsAppNotifications;
import com.bidstay.alerts.GuestAlerts;
import com.bidstay.user.UserRepository;
import com.bidstay.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.bidstay.inventory.InventoryService.STAY_ZONE;
import static com.bidstay.inventory.InventoryService.TURNOVER_SECONDS;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookings;
    private final BidRequestRepository bids;
    private final InventoryService inventory;
    private final PartnerChatService chat;
    private final UserRepository users;
    private final BidStayProperties props;
    private final Clock clock;
    private final WhatsAppNotifications whatsapp;
    private final GuestAlerts alerts;
    private final AuditTrail audit;
    private final JdbcTemplate jdbc;
    private final HourlyStays hourly;
    private final BookingReviews reviews;
    @Value("${bidstay.booking.free-cancellation:false}") private boolean freeCancellation;

    /** Called by BidService inside its transaction: hold rooms and create the unpaid booking atomically. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Booking startPayment(BidRequest bid, BidOffer offer) {
        var details = stayDetails(offer.getRoomTypeId());
        Booking booking = newBooking(bid.getGuestId(), offer.getRoomTypeId(), bid.getCheckIn(), bid.getCheckOut(),
                bid.getRooms(), bid.getGuests(), offer.getOfferedPrice(), details, null);
        booking.setBidRequestId(bid.getId());
        booking.setBidOfferId(offer.getId());
        return bookings.save(booking);
    }

    @Transactional
    public BookingView startListed(Long guestId, ListedStay stay) {
        lockCheckout(guestId, stay.requestId());
        var previous = bookings.findByGuestIdAndDirectRequestId(guestId, stay.requestId());
        if (previous.isPresent()) {
            Booking booking = previous.get();
            if (!booking.getRoomTypeId().equals(stay.roomTypeId()) || !booking.getCheckIn().equals(stay.checkIn())
                    || !booking.getCheckOut().equals(stay.checkOut()) || booking.getRooms() != stay.rooms()
                    || booking.getGuestCount() != stay.guests() || booking.getPricePerNight() == null
                    || booking.getPricePerNight().compareTo(stay.listedRate()) != 0) {
                throw ApiException.conflict("This checkout reference belongs to a different stay");
            }
            return toView(booking);
        }
        long nights = ChronoUnit.DAYS.between(stay.checkIn(), stay.checkOut());
        if (stay.checkIn().isBefore(LocalDate.now(clock)) || nights < 1 || nights > props.maxNights()) {
            throw ApiException.invalid("Choose a stay between 1 and " + props.maxNights() + " nights");
        }
        var details = stayDetails(stay.roomTypeId());
        requireDirectRoom(details, stay.rooms(), stay.guests());
        if (details.listedRate().compareTo(stay.listedRate()) != 0) {
            throw ApiException.conflict("The listed rate changed. Search again to see the current price");
        }
        Booking booking = newBooking(guestId, stay.roomTypeId(), stay.checkIn(), stay.checkOut(),
                stay.rooms(), stay.guests(), details.listedRate(), details, null);
        booking.setDirectRequestId(stay.requestId());
        return toView(bookings.save(booking));
    }

    @Transactional
    public BookingView startHourly(Long guestId, HourlyStay stay) {
        lockCheckout(guestId, stay.requestId());
        var previous = bookings.findByGuestIdAndDirectRequestId(guestId, stay.requestId());
        if (previous.isPresent()) {
            Booking booking = previous.get();
            if (!booking.getRoomTypeId().equals(stay.roomTypeId()) || !booking.getCheckIn().equals(stay.date())
                    || booking.getRooms() != stay.rooms() || booking.getGuestCount() != stay.guests()
                    || booking.getHourlyRate() == null || booking.getHourlyRate().compareTo(stay.ratePerRoom()) != 0
                    || !booking.getArrivalAt().equals(stay.date().atTime(stay.arrival()).atZone(STAY_ZONE).toInstant())
                    || durationHours(booking) != stay.hours()) {
                throw ApiException.conflict("This checkout reference belongs to a different stay");
            }
            return toView(booking);
        }
        inventory.lockPool(stay.roomTypeId());
        var window = hourly.window(stay.date(), stay.arrival(), stay.hours());
        var details = stayDetails(stay.roomTypeId());
        requireDirectRoom(details, stay.rooms(), stay.guests());
        BigDecimal rate = hourly.rate(stay.roomTypeId(), stay.hours());
        if (rate.compareTo(stay.ratePerRoom()) != 0) {
            throw ApiException.conflict("The hourly rate changed. Search again to see the current price");
        }
        Booking booking = newBooking(guestId, stay.roomTypeId(), stay.date(), stay.date(),
                stay.rooms(), stay.guests(), rate, details, window);
        booking.setDirectRequestId(stay.requestId());
        return toView(bookings.save(booking));
    }

    private void lockCheckout(Long guestId, UUID requestId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", (rs, row) -> 0,
                "direct-checkout:" + guestId + ":" + requestId);
    }

    private void requireDirectRoom(StayDetails details, int rooms, int guests) {
        if (!details.active() || details.maxGuests() * rooms < guests) {
            throw ApiException.conflict("This room is unavailable for your party");
        }
    }

    private Booking newBooking(Long guestId, Long roomTypeId, LocalDate checkIn, LocalDate checkOut,
                               int rooms, int guestCount, BigDecimal rate, StayDetails details,
                               HourlyStays.Window shortStay) {
        HourlyStays.Window window = shortStay;
        if (shortStay == null && details.policy().checkInFrom() != null) {
            Instant arrival = checkIn.atTime(details.policy().checkInFrom()).atZone(STAY_ZONE).toInstant();
            Instant departure = checkOut.atTime(details.policy().checkOutBy()).atZone(STAY_ZONE).toInstant();
            window = new HourlyStays.Window(arrival, departure, departure.plusSeconds(TURNOVER_SECONDS));
        }
        if (shortStay == null) {
            inventory.hold(roomTypeId, checkIn, checkOut, rooms,
                    window == null ? null : window.arrival(), window == null ? null : window.blockedUntil());
        } else {
            inventory.requireWindow(roomTypeId, shortStay.arrival(), shortStay.blockedUntil(), rooms);
        }

        Instant now = clock.instant();
        long units = shortStay == null ? ChronoUnit.DAYS.between(checkIn, checkOut) : 1;
        BigDecimal total = rate.multiply(BigDecimal.valueOf(units * rooms))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = total.multiply(props.commissionPct())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        Booking b = new Booking();
        b.setGuestId(guestId);
        b.setHotelId(details.hotelId());
        b.setRoomTypeId(roomTypeId);
        b.setCheckIn(checkIn);
        b.setCheckOut(checkOut);
        b.setRooms(rooms);
        b.setGuestCount(guestCount);
        if (shortStay == null) b.setPricePerNight(rate);
        else b.setHourlyRate(rate);
        if (window != null) {
            b.setArrivalAt(window.arrival());
            b.setDepartureAt(window.departure());
            b.setBlockedUntil(window.blockedUntil());
        }
        b.setTotalAmount(total);
        b.setCommissionPct(props.commissionPct());
        b.setCommissionAmount(commission);
        b.setStatus(BookingStatus.PENDING_PAYMENT);
        Instant payBy = now.plus(props.paymentHold());
        b.setHoldExpiresAt(shortStay != null && shortStay.arrival().isBefore(payBy) ? shortStay.arrival() : payBy);
        b.setFreeCancelUntil(cancellationDeadline(checkIn));
        b.setCreatedAt(now);
        b.setHotelNameAtBooking(details.hotelName());
        b.setHotelAddressAtBooking(details.hotelAddress());
        b.setRoomNameAtBooking(details.roomName());
        b.setCheckInFrom(details.policy().checkInFrom());
        b.setCheckOutBy(details.policy().checkOutBy());
        b.setGuestInstructions(details.policy().guestInstructions());
        return b;
    }

    /**
     * Called for a verified Razorpay capture or a local demo payment.
     */
    @Transactional
    public BookingView confirmPayment(Long bookingId, String paymentRef) {
        Booking b = bookings.lockById(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));

        if (b.getStatus() == BookingStatus.CONFIRMED && paymentRef.equals(b.getPaymentRef())) {
            return toView(b);   // duplicate webhook
        }
        // The payment handler checks the hold deadline before applying a provider capture.
        if (b.getStatus() != BookingStatus.PENDING_PAYMENT || !b.getHoldExpiresAt().isAfter(clock.instant())) {
            throw ApiException.conflict("Booking is " + b.getStatus() + " and cannot be paid");
        }
        if (b.getHourlyRate() == null) inventory.confirmSale(b.getRoomTypeId(), b.getCheckIn(), b.getCheckOut(), b.getRooms());
        b.setStatus(BookingStatus.CONFIRMED);
        b.setPaymentRef(paymentRef);
        updateBid(b, BidStatus.BOOKED);

        // Guest contact is revealed to the hotel only now, after payment.
        String guest = users.findById(b.getGuestId()).map(u -> u.getName() + ", +91 " + u.getPhone()).orElse("guest");
        var message = chat.bot(b.getHotelId(), PartnerMessage.Kind.SYSTEM,
                "*Booking confirmed* #" + b.getId() + "\n" + stayDescription(b) + "\n"
                        + "Total " + Money.inr(b.getTotalAmount()) + " · BidStay commission " + Money.inr(b.getCommissionAmount())
                        + " · Your payout " + Money.inr(b.getTotalAmount().subtract(b.getCommissionAmount())) + "\n"
                        + "Guest: " + guest, null);
        whatsapp.booked(b.getGuestId(), b.getHotelId(), b.getId(), message.getId());
        alerts.booking(b.getGuestId(), b.getId(), "CONFIRMED");
        return toView(b);
    }

    public Instant cancellationDeadline(LocalDate checkIn) {
        if (!freeCancellation) return null;
        return checkIn.minusDays(1).atTime(12, 0).atZone(STAY_ZONE).toInstant();
    }

    @Transactional
    public BookingView cancel(Long guestId, Long bookingId) {
        Booking booking = bookings.lockById(bookingId).filter(b -> b.getGuestId().equals(guestId))
                .orElseThrow(() -> ApiException.notFound("Booking"));
        if (booking.getStatus() == BookingStatus.CANCELLED) return toView(booking);
        Instant now = clock.instant();
        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
            releaseHold(booking);
        } else if (booking.getStatus() == BookingStatus.CONFIRMED && booking.getFreeCancelUntil() != null
                && booking.getFreeCancelUntil().isAfter(now)) {
            if (booking.getHourlyRate() == null) {
                inventory.cancelSale(booking.getRoomTypeId(), booking.getCheckIn(), booking.getCheckOut(), booking.getRooms());
            }
            int queued = jdbc.update("""
                    update payment_captures set status = 'REFUND_PENDING', retry_at = now()
                    where booking_id = ? and status = 'APPLIED'
                    """, bookingId);
            if (queued > 0) audit.record(guestId, Action.REFUND_QUEUED, bookingId, booking.getHotelId());
        } else {
            throw ApiException.conflict("This booking is outside its cancellation window");
        }

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        updateBid(booking, BidStatus.CANCELLED);
        chat.bot(booking.getHotelId(), PartnerMessage.Kind.SYSTEM, "Booking #" + bookingId + " was cancelled.", null);
        alerts.booking(booking.getGuestId(), bookingId, "CANCELLED");
        audit.record(guestId, Action.BOOKING_CANCELLED, bookingId, booking.getHotelId());
        return toView(booking);
    }

    /** Scheduler: guest never paid, so give the rooms back. */
    @Transactional
    public int releaseExpiredHolds() {
        List<Booking> stale = bookings.findTop100ByStatusAndHoldExpiresAtBeforeOrderByHoldExpiresAtAscIdAsc(BookingStatus.PENDING_PAYMENT, clock.instant());
        for (Booking b : stale) {
            releaseHold(b);
            b.setStatus(BookingStatus.EXPIRED);
            updateBid(b, BidStatus.EXPIRED);
        }
        return stale.size();
    }

    private void releaseHold(Booking b) {
        if (b.getHourlyRate() == null) inventory.release(b.getRoomTypeId(), b.getCheckIn(), b.getCheckOut(), b.getRooms());
    }

    private long durationHours(Booking b) {
        return Duration.between(b.getArrivalAt(), b.getDepartureAt()).toHours();
    }

    private String stayDescription(Booking booking) {
        if (booking.getHourlyRate() == null) {
            return booking.getCheckIn() + " to " + booking.getCheckOut() + " · " + booking.getRooms()
                    + " room(s) at " + Money.inr(booking.getPricePerNight()) + " per night";
        }
        var time = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(STAY_ZONE);
        return time.format(booking.getArrivalAt()) + " to " + time.format(booking.getDepartureAt()) + " IST · " + booking.getRooms()
                + " room(s) at " + Money.inr(booking.getHourlyRate()) + " for " + durationHours(booking) + " hours";
    }

    /** Latest booking created for a bid, or null. Lets the app jump from a bid to its payment/confirmation. */
    @Transactional(readOnly = true)
    public Long latestBookingIdForBid(Long bidId) {
        return bookings.findByBidRequestIdOrderByIdDesc(bidId).stream().findFirst().map(Booking::getId).orElse(null);
    }

    @Transactional(readOnly = true)
    public BookingView get(Long guestId, Long bookingId) {
        Booking b = bookings.findById(bookingId)
                .filter(x -> x.getGuestId().equals(guestId))
                .orElseThrow(() -> ApiException.notFound("Booking"));
        return toView(b);
    }

    @Transactional(readOnly = true)
    public List<DirectTrip> directTrips(Long guestId, long before) {
        return bookings.findTop50ByGuestIdAndDirectRequestIdIsNotNullAndIdLessThanOrderByIdDesc(guestId, before).stream()
                .map(b -> new DirectTrip(b.getId(), b.getStatus(), b.getHotelNameAtBooking(), b.getCheckIn(), b.getCheckOut(),
                        b.getRooms(), b.getTotalAmount(), shortStay(b)))
                .toList();
    }

    private void updateBid(Booking booking, BidStatus status) {
        if (booking.getBidRequestId() != null) {
            bids.findById(booking.getBidRequestId()).ifPresent(bid -> bid.setStatus(status));
        }
    }

    public BookingView toView(Booking b) {
        boolean paid = b.getPaymentRef() != null;
        boolean revealHotel = paid || b.getStatus() == BookingStatus.CONFIRMED;
        String name = b.getHotelNameAtBooking();
        String address = b.getHotelAddressAtBooking();
        String roomName = b.getRoomNameAtBooking();
        if (roomName == null) {
            var legacy = stayDetails(b.getRoomTypeId());
            name = legacy.hotelName();
            address = legacy.hotelAddress();
            roomName = legacy.roomName();
        }
        StayPolicy policy = b.getCheckInFrom() == null ? null
                : new StayPolicy(b.getCheckInFrom(), b.getCheckOutBy(), b.getGuestInstructions());

        BookingView.PaymentReceipt receipt = null;
        String refundStatus = null;
        if (paid) {
            var capture = jdbc.query("""
                    select captured_at, refund_id, status from payment_captures
                    where provider_payment_id = ? and booking_id = ?
                    """, (rs, row) -> new CaptureDetails(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3)),
                    b.getPaymentRef(), b.getId()).stream().findFirst();
            receipt = capture.map(payment -> new BookingView.PaymentReceipt(
                    b.getPaymentRef(), payment.recordedAt(), payment.refundReference(), false))
                    .orElseGet(() -> new BookingView.PaymentReceipt(b.getPaymentRef(), null, null, true));
            refundStatus = capture.map(CaptureDetails::status).filter(status -> status.startsWith("REFUND_")).orElse(null);
        } else if (b.getStatus() == BookingStatus.CANCELLED || b.getStatus() == BookingStatus.EXPIRED) {
            refundStatus = jdbc.query("""
                    select status from payment_captures where booking_id = ? and status like 'REFUND_%'
                    order by captured_at desc limit 1
                    """, (rs, row) -> rs.getString(1), b.getId()).stream().findFirst().orElse(null);
        }
        return new BookingView(b.getId(), b.getStatus(), b.getCheckIn(), b.getCheckOut(), b.getRooms(),
                b.getPricePerNight(), b.getTotalAmount(), b.getHoldExpiresAt(),
                revealHotel || b.getDirectRequestId() != null ? name : null,
                revealHotel ? address : null, b.getFreeCancelUntil(), refundStatus,
                ChronoUnit.DAYS.between(b.getCheckIn(), b.getCheckOut()), roomName, policy, receipt, shortStay(b), reviews.statusFor(b));
    }

    private BookingView.ShortStay shortStay(Booking b) {
        return b.getHourlyRate() == null ? null
                : new BookingView.ShortStay(b.getArrivalAt(), b.getDepartureAt(), durationHours(b), b.getHourlyRate());
    }

    private StayDetails stayDetails(Long roomTypeId) {
        return jdbc.query("""
                select h.name, h.address, rt.name, p.check_in_from, p.check_out_by, p.guest_instructions,
                       h.id, h.status = 'ACTIVE', rt.max_guests, rt.listed_price
                from room_types rt join hotels h on h.id = rt.hotel_id
                left join hotel_stay_policies p on p.hotel_id = h.id where rt.id = ?
                """, (rs, row) -> new StayDetails(rs.getString(1), rs.getString(2), rs.getString(3),
                new StayPolicy(rs.getObject(4, LocalTime.class), rs.getObject(5, LocalTime.class), rs.getString(6)),
                rs.getLong(7), rs.getBoolean(8), rs.getInt(9), rs.getBigDecimal(10)),
                roomTypeId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Room type"));
    }

    private record StayDetails(String hotelName, String hotelAddress, String roomName, StayPolicy policy,
                               long hotelId, boolean active, int maxGuests, BigDecimal listedRate) {}
    private record CaptureDetails(Instant recordedAt, String refundReference, String status) {}
    public record DirectTrip(long bookingId, BookingStatus status, String hotelName, LocalDate checkIn,
                             LocalDate checkOut, int rooms, BigDecimal totalAmount, BookingView.ShortStay shortStay) {}
}
