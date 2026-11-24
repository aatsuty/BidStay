package com.bidstay.partner;

import com.bidstay.auth.CallerId;
import com.bidstay.bidding.BidDtos.HotelOfferView;
import com.bidstay.bidding.BidDtos.HotelRespond;
import com.bidstay.hotel.DocType;
import com.bidstay.partner.PartnerDtos.*;
import com.bidstay.user.AccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Hotelier portal API. Every hotel-specific route checks that the caller works at that hotel.
 */
@RestController
@RequestMapping("/api/v1/partner")
@RequiredArgsConstructor
public class PartnerController {

    private final PartnerService service;
    private final AccessService access;

    @GetMapping("/hotels")
    public List<MyHotel> myHotels(@CallerId Long userId) {
        access.requireHotelStaff(userId);
        return service.myHotels(access.hotelIdsOf(userId));
    }

    @PostMapping("/hotels")
    @ResponseStatus(HttpStatus.CREATED)
    public MyHotel register(@CallerId Long userId, @Valid @RequestBody RegisterHotel body) {
        access.requireHotelStaff(userId);
        return service.register(userId, body);
    }

    @GetMapping("/hotels/{hotelId}/dashboard")
    public Dashboard dashboard(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireHotelAccess(userId, hotelId);
        return service.dashboard(hotelId);
    }

    @GetMapping("/hotels/{hotelId}/offers")
    public List<HotelOfferView> offers(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireHotelAccess(userId, hotelId);
        return service.pendingOffers(hotelId);
    }

    @PostMapping("/hotels/{hotelId}/offers/{offerId}/respond")
    public Map<String, Object> respond(@CallerId Long userId, @PathVariable Long hotelId,
                                       @PathVariable Long offerId, @Valid @RequestBody HotelRespond body) {
        access.requireHotelAccess(userId, hotelId);
        return service.respond(hotelId, offerId, body);
    }

    @GetMapping("/hotels/{hotelId}/chat")
    public List<ChatMessageView> chat(@CallerId Long userId, @PathVariable Long hotelId,
                                      @RequestParam(defaultValue = "0") long after) {
        access.requireHotelAccess(userId, hotelId);
        return service.chatHistory(hotelId, after);
    }

    @PostMapping("/hotels/{hotelId}/chat")
    public List<ChatMessageView> chatSend(@CallerId Long userId, @PathVariable Long hotelId,
                                          @Valid @RequestBody ChatSend body) {
        access.requireHotelAccess(userId, hotelId);
        return service.handleChatText(hotelId, body.text());
    }

    @GetMapping("/hotels/{hotelId}/rates")
    public Rates rates(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireHotelAccess(userId, hotelId);
        return service.rates(hotelId);
    }

    @PutMapping("/hotels/{hotelId}/rates")
    public Rates updateRates(@CallerId Long userId, @PathVariable Long hotelId, @Valid @RequestBody RatesUpdate body) {
        access.requireHotelAccess(userId, hotelId);
        return service.updateRates(hotelId, body);
    }

    @GetMapping("/hotels/{hotelId}/availability")
    public List<RoomAvailability> availability(@CallerId Long userId, @PathVariable Long hotelId,
                                               @RequestParam(defaultValue = "14") int days) {
        access.requireHotelAccess(userId, hotelId);
        return service.availability(hotelId, days);
    }

    @PutMapping("/hotels/{hotelId}/availability")
    public List<RoomAvailability> setAvailability(@CallerId Long userId, @PathVariable Long hotelId,
                                                  @Valid @RequestBody AvailabilityUpdate body) {
        access.requireHotelAccess(userId, hotelId);
        service.setAvailability(hotelId, body);
        return service.availability(hotelId, 14);
    }

    @GetMapping("/hotels/{hotelId}/bookings")
    public List<BookingRow> bookings(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireHotelAccess(userId, hotelId);
        return service.bookings(hotelId);
    }

    @GetMapping("/hotels/{hotelId}/documents")
    public List<DocumentView> documents(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireHotelAccess(userId, hotelId);
        return service.documents(hotelId);
    }

    @PutMapping("/hotels/{hotelId}/documents/{type}")
    public List<DocumentView> submitDocument(@CallerId Long userId, @PathVariable Long hotelId,
                                             @PathVariable DocType type, @Valid @RequestBody DocumentInput body) {
        access.requireHotelAccess(userId, hotelId);
        return service.submitDocument(hotelId, type, body.reference());
    }
}
