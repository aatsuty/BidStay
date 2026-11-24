package com.bidstay.booking;

import com.bidstay.audit.AuditTrail;
import com.bidstay.audit.AuditTrail.Action;
import com.bidstay.bidding.BidOffer;
import com.bidstay.bidding.BidRequest;
import com.bidstay.bidding.BidRequestRepository;
import com.bidstay.bidding.BidStatus;
import com.bidstay.common.ApiException;
import com.bidstay.common.BidStayProperties;
import com.bidstay.hotel.Hotel;
import com.bidstay.common.Money;
import com.bidstay.hotel.HotelRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookings;
    private final BidRequestRepository bids;
    private final HotelRepository hotels;
    private final InventoryService inventory;
    private final PartnerChatService chat;
    private final UserRepository users;
    private final BidStayProperties props;
    private final Clock clock;
    private final WhatsAppNotifications whatsapp;
    private final GuestAlerts alerts;
    private final AuditTrail audit;
    private final JdbcTemplate jdbc;
    @Value("${bidstay.booking.free-cancellation:false}") private boolean freeCancellation;

    /** Called by BidService inside its transaction: hold rooms and create the unpaid booking atomically. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Booking startPayment(BidRequest bid, BidOffer offer) {
        inventory.hold(offer.getRoomTypeId(), bid.getCheckIn(), bid.getCheckOut(), bid.getRooms());

        Instant now = clock.instant();
        BigDecimal total = offer.getOfferedPrice()
                .multiply(BigDecimal.valueOf(bid.nights()))
                .multiply(BigDecimal.valueOf(bid.getRooms()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = total.multiply(props.commissionPct())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        Booking b = new Booking();
        b.setBidRequestId(bid.getId());
        b.setBidOfferId(offer.getId());
        b.setGuestId(bid.getGuestId());
        b.setHotelId(offer.getHotelId());
        b.setRoomTypeId(offer.getRoomTypeId());
        b.setCheckIn(bid.getCheckIn());
        b.setCheckOut(bid.getCheckOut());
        b.setRooms(bid.getRooms());
        b.setPricePerNight(offer.getOfferedPrice());
        b.setTotalAmount(total);
        b.setCommissionPct(props.commissionPct());
        b.setCommissionAmount(commission);
        b.setStatus(BookingStatus.PENDING_PAYMENT);
        b.setHoldExpiresAt(now.plus(props.paymentHold()));
        b.setFreeCancelUntil(cancellationDeadline(bid.getCheckIn()));
        b.setCreatedAt(now);
        return bookings.save(b);
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
        if (b.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("Booking is " + b.getStatus() + " and cannot be paid");
        }
        inventory.confirmSale(b.getRoomTypeId(), b.getCheckIn(), b.getCheckOut(), b.getRooms());
        b.setStatus(BookingStatus.CONFIRMED);
        b.setPaymentRef(paymentRef);
        bids.findById(b.getBidRequestId()).ifPresent(bid -> bid.setStatus(BidStatus.BOOKED));

        // Guest contact is revealed to the hotel only now, after payment.
        String guest = users.findById(b.getGuestId()).map(u -> u.getName() + ", +91 " + u.getPhone()).orElse("guest");
        var message = chat.bot(b.getHotelId(), PartnerMessage.Kind.SYSTEM,
                "*Booking confirmed* #" + b.getId() + "\n" + b.getCheckIn() + " to " + b.getCheckOut() + " · " + b.getRooms()
                        + " room(s) at " + Money.inr(b.getPricePerNight()) + " per night\n"
                        + "Total " + Money.inr(b.getTotalAmount()) + " · BidStay commission " + Money.inr(b.getCommissionAmount())
                        + " · Your payout " + Money.inr(b.getTotalAmount().subtract(b.getCommissionAmount())) + "\n"
                        + "Guest: " + guest, null);
        whatsapp.booked(b.getGuestId(), b.getHotelId(), b.getId(), message.getId());
        alerts.booking(b.getGuestId(), b.getId(), "CONFIRMED");
        return toView(b);
    }

    public Instant cancellationDeadline(LocalDate checkIn) {
        if (!freeCancellation) return null;
        return checkIn.minusDays(1).atTime(12, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }

    @Transactional
    public BookingView cancel(Long guestId, Long bookingId) {
        Booking booking = bookings.lockById(bookingId).filter(b -> b.getGuestId().equals(guestId))
                .orElseThrow(() -> ApiException.notFound("Booking"));
        if (booking.getStatus() == BookingStatus.CANCELLED) return toView(booking);
        Instant now = clock.instant();
        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
            inventory.release(booking.getRoomTypeId(), booking.getCheckIn(), booking.getCheckOut(), booking.getRooms());
        } else if (booking.getStatus() == BookingStatus.CONFIRMED && booking.getFreeCancelUntil() != null
                && booking.getFreeCancelUntil().isAfter(now)) {
            inventory.cancelSale(booking.getRoomTypeId(), booking.getCheckIn(), booking.getCheckOut(), booking.getRooms());
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
        bids.findById(booking.getBidRequestId()).ifPresent(bid -> bid.setStatus(BidStatus.CANCELLED));
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
            inventory.release(b.getRoomTypeId(), b.getCheckIn(), b.getCheckOut(), b.getRooms());
            b.setStatus(BookingStatus.EXPIRED);
            bids.findById(b.getBidRequestId()).ifPresent(bid -> bid.setStatus(BidStatus.EXPIRED));
        }
        return stale.size();
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

    public BookingView toView(Booking b) {
        String name = null, address = null;
        if (b.getStatus() == BookingStatus.CONFIRMED) {
            Hotel h = hotels.findById(b.getHotelId()).orElseThrow();
            name = h.getName();
            address = h.getAddress();
        }
        String refundStatus = null;
        if (b.getStatus() == BookingStatus.CANCELLED || b.getStatus() == BookingStatus.EXPIRED) {
            refundStatus = jdbc.query("""
                    select status from payment_captures where booking_id = ? and status like 'REFUND_%'
                    order by captured_at desc limit 1
                    """, (rs, row) -> rs.getString(1), b.getId()).stream().findFirst().orElse(null);
        }
        return new BookingView(b.getId(), b.getStatus(), b.getCheckIn(), b.getCheckOut(), b.getRooms(),
                b.getPricePerNight(), b.getTotalAmount(), b.getHoldExpiresAt(), name, address, b.getFreeCancelUntil(), refundStatus);
    }
}
