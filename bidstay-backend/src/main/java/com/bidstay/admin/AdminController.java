package com.bidstay.admin;

import com.bidstay.admin.AdminDtos.*;
import com.bidstay.hotel.DocType;
import com.bidstay.hotel.HotelStatus;
import com.bidstay.user.AccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin portal API. Every route requires the ADMIN role. AUTH PLACEHOLDER: X-User-Id until JWT login exists. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService service;
    private final AccessService access;

    @GetMapping("/stats")
    public Stats stats(@RequestHeader("X-User-Id") Long userId) {
        access.requireAdmin(userId);
        return service.stats();
    }

    @GetMapping("/hotels")
    public List<HotelRow> hotels(@RequestHeader("X-User-Id") Long userId, @RequestParam(required = false) HotelStatus status) {
        access.requireAdmin(userId);
        return service.hotels(status);
    }

    @PostMapping("/hotels/{hotelId}/documents/{type}/{decision}")
    public HotelRow verifyDocument(@RequestHeader("X-User-Id") Long userId, @PathVariable Long hotelId,
                                   @PathVariable DocType type, @PathVariable String decision) {
        access.requireAdmin(userId);
        return service.verifyDocument(hotelId, type, "verify".equals(decision));   // verify | unverify
    }

    @PostMapping("/hotels/{hotelId}/approve")
    public HotelRow approve(@RequestHeader("X-User-Id") Long userId, @PathVariable Long hotelId) {
        access.requireAdmin(userId);
        return service.approve(hotelId);
    }

    @PostMapping("/hotels/{hotelId}/reject")
    public HotelRow reject(@RequestHeader("X-User-Id") Long userId, @PathVariable Long hotelId, @Valid @RequestBody Reject body) {
        access.requireAdmin(userId);
        return service.reject(hotelId, body.reason());
    }

    @PostMapping("/hotels/{hotelId}/suspend")
    public HotelRow suspend(@RequestHeader("X-User-Id") Long userId, @PathVariable Long hotelId) {
        access.requireAdmin(userId);
        return service.setSuspended(hotelId, true);
    }

    @PostMapping("/hotels/{hotelId}/reinstate")
    public HotelRow reinstate(@RequestHeader("X-User-Id") Long userId, @PathVariable Long hotelId) {
        access.requireAdmin(userId);
        return service.setSuspended(hotelId, false);
    }

    @GetMapping("/bookings")
    public List<BookingRow> bookings(@RequestHeader("X-User-Id") Long userId) {
        access.requireAdmin(userId);
        return service.bookings();
    }

    @GetMapping("/bids")
    public List<BidRow> bids(@RequestHeader("X-User-Id") Long userId) {
        access.requireAdmin(userId);
        return service.bids();
    }
}
