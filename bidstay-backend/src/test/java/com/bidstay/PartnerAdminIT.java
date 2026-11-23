package com.bidstay;

import com.bidstay.admin.AdminDtos.HotelRow;
import com.bidstay.admin.AdminService;
import com.bidstay.bidding.BidDtos.BidView;
import com.bidstay.bidding.BidDtos.CreateBid;
import com.bidstay.bidding.BidService;
import com.bidstay.bidding.OfferStatus;
import com.bidstay.common.ApiException;
import com.bidstay.hotel.*;
import com.bidstay.partner.PartnerChatService;
import com.bidstay.partner.PartnerService;
import com.bidstay.user.AccessService;
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
 * Seed facts: users 1,2 guests; 3 owner of hotels 1 (Sunrise: min 1200) and 2; 4 admin; 5 owner of the two pending hotels.
 * Delhi Grand Stay has 3 of 4 documents; Pahar Palace has all 4. Each test uses its own dates.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
class PartnerAdminIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired BidService bids;
    @Autowired PartnerService partner;
    @Autowired PartnerChatService chat;
    @Autowired AdminService admin;
    @Autowired AccessService access;
    @Autowired HotelRepository hotels;

    Hotel sunrise() { return hotels.findByName("Hotel Sunrise Paharganj").orElseThrow(); }

    BidView bid(int offset, String price) {
        LocalDate in = LocalDate.now().plusDays(offset);
        return bids.create(1L, new CreateBid("Delhi", in, in.plusDays(1), 1, 2, RoomCategory.STANDARD,
                new BigDecimal(price), List.of(sunrise().getId())));
    }

    @Test
    void manualModeAlwaysWaitsForTheOwner() {
        Hotel h = sunrise();
        h.setAutoMode(false);
        hotels.save(h);
        try {
            assertEquals(OfferStatus.PENDING, bid(50, "1500").offers().get(0).status());   // above the 1200 minimum
        } finally {
            h.setAutoMode(true);
            hotels.save(h);
        }
    }

    @Test
    void autoCounterRepliesToBidsJustBelowTheMinimum() {
        Hotel h = sunrise();
        h.setAutoCounter(true);
        h.setAutoCounterPct(20);                  // floor = 1200 * 0.8 = 960
        hotels.save(h);
        try {
            var close = bid(52, "1000").offers().get(0);
            assertEquals(OfferStatus.COUNTERED, close.status());
            assertEquals(0, new BigDecimal("1200").compareTo(close.pricePerNight()));
            assertTrue(close.autoAccepted());
            assertEquals(OfferStatus.PENDING, bid(54, "800").offers().get(0).status());     // too low: waits for the owner
        } finally {
            h.setAutoCounter(false);
            hotels.save(h);
        }
    }

    @Test
    void whatsAppStyleRepliesWork() {
        BidView v = bid(56, "1100");
        assertEquals(OfferStatus.PENDING, v.offers().get(0).status());
        assertTrue(chat.history(sunrise().getId(), 0).stream().anyMatch(m -> m.getKind().name().equals("REQUEST")));

        long offerId = v.offers().get(0).offerId();
        BidView newer = bid(57, "1100");
        var ambiguous = partner.handleChatText(sunrise().getId(), "YES if breakfast is included");
        assertTrue(ambiguous.get(ambiguous.size() - 1).body().contains("request and round"));
        assertEquals(OfferStatus.PENDING, bids.get(1L, newer.bidId()).offers().get(0).status());

        bids.raise(1L, v.bidId(), new BigDecimal("1150"));
        partner.handleChatText(sunrise().getId(), "YES " + offerId + "/1");
        assertEquals(OfferStatus.PENDING, bids.get(1L, v.bidId()).offers().get(0).status());

        var msgs = partner.handleChatText(sunrise().getId(), "counter 1500 " + offerId + "/2");
        assertEquals("HOTEL", msgs.get(0).sender());
        assertEquals(OfferStatus.COUNTERED, bids.get(1L, v.bidId()).offers().get(0).status());
        assertEquals(OfferStatus.PENDING, bids.get(1L, newer.bidId()).offers().get(0).status());
    }

    @Test
    void ownersSeeOnlyTheirOwnHotelsAndOnlyAdminsApprove() {
        Long pahar = hotels.findByName("Pahar Palace").orElseThrow().getId();
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> access.requireHotelAccess(3L, pahar)).getStatus());
        assertDoesNotThrow(() -> access.requireHotelAccess(5L, pahar));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> access.requireAdmin(1L)).getStatus());
        assertDoesNotThrow(() -> access.requireAdmin(4L));
    }

    @Test
    void approvalNeedsAllFourVerifiedDocuments() {
        Long grand = hotels.findByName("Delhi Grand Stay").orElseThrow().getId();
        assertThrows(ApiException.class, () -> admin.approve(grand));                       // nothing verified
        for (DocType t : List.of(DocType.GST, DocType.LICENCE, DocType.OWNER_ID)) admin.verifyDocument(grand, t, true);
        assertThrows(ApiException.class, () -> admin.verifyDocument(grand, DocType.PHOTOS, true));   // never submitted
        partner.submitDocument(grand, DocType.PHOTOS, "https://example.com/photos");
        admin.verifyDocument(grand, DocType.PHOTOS, true);

        HotelRow live = admin.approve(grand);
        assertEquals(HotelStatus.ACTIVE, live.status());
        assertTrue(live.allDocsVerified());
    }

    @Test
    void rejectedHotelReturnsToReviewWhenOwnerResubmits() {
        Long pahar = hotels.findByName("Pahar Palace").orElseThrow().getId();
        assertEquals(HotelStatus.REJECTED, admin.reject(pahar, "GST number does not match").status());
        partner.submitDocument(pahar, DocType.GST, "07AAAAA0000F1Z5 (corrected)");
        assertEquals(HotelStatus.PENDING_VERIFICATION, hotels.findById(pahar).orElseThrow().getStatus());
    }

    @Test
    void statsReportTheThreeTargetsFromThePlan() {
        var s = admin.stats();
        assertEquals(3, s.targets().size());
        assertTrue(s.liveHotels() >= 4);
    }
}
