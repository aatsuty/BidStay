package com.bidstay.partner;

import com.bidstay.auth.CallerId;
import com.bidstay.bidding.BidDtos.HotelOfferView;
import com.bidstay.bidding.BidDtos.HotelRespond;
import com.bidstay.hotel.DocType;
import com.bidstay.hotel.HotelDocumentFiles;
import com.bidstay.hotel.HourlyStays;
import com.bidstay.hotel.StayPolicy;
import com.bidstay.partner.PartnerDtos.*;
import com.bidstay.user.AccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import com.bidstay.common.ApiException;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
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
    private final HotelDocumentFiles files;
    private final Environment environment;
    private final HourlyStays hourly;

    @GetMapping("/hotels/{hotelId}/hourly")
    public List<HourlyStays.RoomRates> hourly(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireStaffManager(userId, hotelId);
        return hourly.partnerRates(hotelId);
    }

    @PutMapping("/hotels/{hotelId}/hourly/{roomTypeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateHourly(@CallerId Long userId, @PathVariable Long hotelId, @PathVariable Long roomTypeId,
                             @Valid @RequestBody HourlyStays.RatesChange rates) {
        access.requireStaffManager(userId, hotelId);
        hourly.update(userId, hotelId, roomTypeId, rates);
    }

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

    @GetMapping("/hotels/{hotelId}/stay-policy")
    public StayPolicy stayPolicy(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireStaffManager(userId, hotelId);
        return service.stayPolicy(hotelId);
    }

    @PutMapping("/hotels/{hotelId}/stay-policy")
    public StayPolicy updateStayPolicy(@CallerId Long userId, @PathVariable Long hotelId,
                                       @Valid @RequestBody StayPolicy policy) {
        access.requireStaffManager(userId, hotelId);
        return service.updateStayPolicy(userId, hotelId, policy);
    }

    @GetMapping("/hotels/{hotelId}/documents")
    public List<DocumentView> documents(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireHotelAccess(userId, hotelId);
        return service.documents(hotelId);
    }

    @PostMapping(value = "/hotels/{hotelId}/documents/{type}/file", consumes = "multipart/form-data")
    public List<DocumentView> upload(@CallerId Long userId, @PathVariable Long hotelId,
                                     @PathVariable DocType type, @RequestParam MultipartFile file) throws IOException {
        access.requireHotelAccess(userId, hotelId);
        files.upload(userId, hotelId, type, file);
        return service.documents(hotelId);
    }

    @PutMapping("/hotels/{hotelId}/documents/{type}")
    public List<DocumentView> submitDocument(@CallerId Long userId, @PathVariable Long hotelId,
                                             @PathVariable DocType type, @Valid @RequestBody DocumentInput body) {
        access.requireHotelAccess(userId, hotelId);
        if (!environment.acceptsProfiles(Profiles.of("dev"))) throw ApiException.invalid("Upload the document file");
        return service.submitDocument(hotelId, type, body.reference());
    }
}
