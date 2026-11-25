package com.bidstay.bidding;

import com.bidstay.auth.CallerId;
import com.bidstay.bidding.BidDtos.*;
import com.bidstay.booking.BookingView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Guest endpoints.
 */
@RestController
@RequestMapping("/api/v1/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BidView create(@CallerId(guestOnly = true) Long userId, @Valid @RequestBody CreateBid body) {
        return service.create(userId, body);
    }

    @GetMapping
    public List<BidView> mine(@CallerId(guestOnly = true) Long userId) {
        return service.listForGuest(userId);
    }

    @GetMapping("/{bidId}")
    public BidView get(@CallerId(guestOnly = true) Long userId, @PathVariable Long bidId) {
        return service.get(userId, bidId);
    }

    @PostMapping("/{bidId}/raise")
    public BidView raise(@CallerId(guestOnly = true) Long userId, @PathVariable Long bidId, @Valid @RequestBody Raise body) {
        return service.raise(userId, bidId, body.pricePerNight());
    }

    @PostMapping("/{bidId}/cancel")
    public BidView cancel(@CallerId(guestOnly = true) Long userId, @PathVariable Long bidId) {
        return service.cancel(userId, bidId);
    }

    @PostMapping("/{bidId}/offers/{offerId}/accept")
    public BookingView accept(@CallerId(guestOnly = true) Long userId, @PathVariable Long bidId, @PathVariable Long offerId) {
        return service.acceptOffer(userId, bidId, offerId);
    }
}
