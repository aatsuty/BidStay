package com.bidstay.bidding;

import com.bidstay.bidding.BidDtos.*;
import com.bidstay.booking.BookingView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Guest endpoints.
 * AUTH PLACEHOLDER: X-User-Id header stands in for the logged-in user until JWT/OTP auth is added.
 * Replace @RequestHeader with the authenticated principal before any real deployment.
 */
@RestController
@RequestMapping("/api/v1/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BidView create(@RequestHeader("X-User-Id") Long userId, @Valid @RequestBody CreateBid body) {
        return service.create(userId, body);
    }

    @GetMapping
    public List<BidView> mine(@RequestHeader("X-User-Id") Long userId) {
        return service.listForGuest(userId);
    }

    @GetMapping("/{bidId}")
    public BidView get(@RequestHeader("X-User-Id") Long userId, @PathVariable Long bidId) {
        return service.get(userId, bidId);
    }

    @PostMapping("/{bidId}/raise")
    public BidView raise(@RequestHeader("X-User-Id") Long userId, @PathVariable Long bidId, @Valid @RequestBody Raise body) {
        return service.raise(userId, bidId, body.pricePerNight());
    }

    @PostMapping("/{bidId}/offers/{offerId}/accept")
    public BookingView accept(@RequestHeader("X-User-Id") Long userId, @PathVariable Long bidId, @PathVariable Long offerId) {
        return service.acceptOffer(userId, bidId, offerId);
    }
}
