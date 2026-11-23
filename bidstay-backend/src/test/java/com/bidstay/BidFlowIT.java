package com.bidstay;

import com.bidstay.bidding.BidDtos.*;
import com.bidstay.bidding.BidService;
import com.bidstay.bidding.HotelAction;
import com.bidstay.bidding.OfferStatus;
import com.bidstay.booking.BookingService;
import com.bidstay.booking.BookingStatus;
import com.bidstay.booking.BookingView;
import com.bidstay.common.ApiException;
import com.bidstay.hotel.HotelRepository;
import com.bidstay.hotel.HotelStatus;
import com.bidstay.hotel.RoomCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs against a real PostgreSQL (needs Docker). The "dev" profile loads the seed data:
 * Hotel Sunrise Paharganj, STANDARD room: listed 1800, hidden minimum 1200, 10 rooms.
 * Guests are user ids 1 and 2. Each test uses its own dates so tests do not share inventory.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
class BidFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired BidService bidService;
    @Autowired BookingService bookingService;
    @Autowired HotelRepository hotels;

    Long sunrise() {
        return hotels.findByName("Hotel Sunrise Paharganj").orElseThrow().getId();
    }

    CreateBid bid(int dayOffset, int rooms, String price) {
        LocalDate in = LocalDate.now().plusDays(dayOffset);
        return new CreateBid("Delhi", in, in.plusDays(2), rooms, 2, RoomCategory.STANDARD,
                new BigDecimal(price), List.of(sunrise()));
    }

    @Test
    void bidAboveHiddenMinimumIsAutoAccepted() {
        BidView v = bidService.create(1L, bid(5, 1, "1300"));
        OfferView o = v.offers().get(0);
        assertEquals(OfferStatus.ACCEPTED, o.status());
        assertTrue(o.autoAccepted());
        assertEquals(0, new BigDecimal("1300").compareTo(o.pricePerNight()));
    }

    @Test
    void counterOfferThenPayConfirmsBookingAndRevealsHotel() {
        BidView v = bidService.create(1L, bid(10, 1, "1100"));
        Long offerId = v.offers().get(0).offerId();
        assertEquals(OfferStatus.PENDING, v.offers().get(0).status());

        OfferStatus after = bidService.respond(sunrise(), offerId, new HotelRespond(HotelAction.COUNTER, new BigDecimal("1500"), 1));
        assertEquals(OfferStatus.COUNTERED, after);

        BookingView booking = bidService.acceptOffer(1L, v.bidId(), offerId);
        assertEquals(BookingStatus.PENDING_PAYMENT, booking.status());
        assertEquals(0, new BigDecimal("3000.00").compareTo(booking.totalAmount()));   // 1500 x 2 nights x 1 room
        assertNull(booking.hotelAddress());                                           // hidden until paid

        BookingView paid = bookingService.confirmPayment(booking.bookingId(), "pay_test_1");
        assertEquals(BookingStatus.CONFIRMED, paid.status());
        assertNotNull(paid.hotelAddress());
        // duplicate webhook is harmless
        assertEquals(BookingStatus.CONFIRMED, bookingService.confirmPayment(booking.bookingId(), "pay_test_1").status());
    }

    @Test
    void raisingPriceCrossesTheMinimumAndRoundsAreLimited() {
        BidView v = bidService.create(1L, bid(15, 1, "900"));
        assertEquals(OfferStatus.PENDING, v.offers().get(0).status());

        v = bidService.raise(1L, v.bidId(), new BigDecimal("1250"));      // round 2, now >= 1200
        assertEquals(OfferStatus.ACCEPTED, v.offers().get(0).status());

        v = bidService.raise(1L, v.bidId(), new BigDecimal("1300"));      // round 3
        Long bidId = v.bidId();
        ApiException ex = assertThrows(ApiException.class, () -> bidService.raise(1L, bidId, new BigDecimal("1400")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void sameRoomsCannotBeSoldTwice() {
        // 6 + 6 rooms against a 10-room inventory: both bids look fine, only one can be held.
        BidView a = bidService.create(1L, bid(30, 6, "1300"));
        BidView b = bidService.create(2L, bid(30, 6, "1300"));
        assertEquals(OfferStatus.ACCEPTED, a.offers().get(0).status());
        assertEquals(OfferStatus.ACCEPTED, b.offers().get(0).status());

        bidService.acceptOffer(1L, a.bidId(), a.offers().get(0).offerId());

        ApiException ex = assertThrows(ApiException.class,
                () -> bidService.acceptOffer(2L, b.bidId(), b.offers().get(0).offerId()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void suspendedHotelCannotReplyOrReceiveBookingsFromExistingBids() {
        BidView accepted = bidService.create(1L, bid(45, 1, "1300"));
        BidView pending = bidService.create(1L, bid(48, 1, "1100"));
        var hotel = hotels.findById(sunrise()).orElseThrow();
        hotel.setStatus(HotelStatus.SUSPENDED);
        hotels.save(hotel);
        try {
            ApiException bookingError = assertThrows(ApiException.class, () ->
                    bidService.acceptOffer(1L, accepted.bidId(), accepted.offers().get(0).offerId()));
            assertEquals(HttpStatus.CONFLICT, bookingError.getStatus());

            ApiException replyError = assertThrows(ApiException.class, () ->
                    bidService.respond(hotel.getId(), pending.offers().get(0).offerId(),
                            new HotelRespond(HotelAction.ACCEPT, null, 1)));
            assertEquals(HttpStatus.CONFLICT, replyError.getStatus());

            BidView raised = bidService.raise(1L, pending.bidId(), new BigDecimal("1500"));
            assertEquals(OfferStatus.UNAVAILABLE, raised.offers().get(0).status());
        } finally {
            hotel.setStatus(HotelStatus.ACTIVE);
            hotels.save(hotel);
        }
    }

    @Test
    void hotelCannotAnswerAnotherHotelsOffer() {
        BidView v = bidService.create(1L, bid(40, 1, "1000"));
        Long offerId = v.offers().get(0).offerId();
        Long otherHotel = hotels.findByName("Hotel Metro Inn").orElseThrow().getId();
        assertThrows(ApiException.class,
                () -> bidService.respond(otherHotel, offerId, new HotelRespond(HotelAction.ACCEPT, null, 1)));
    }
}
