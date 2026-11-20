package com.bidstay.booking;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService service;

    @GetMapping("/{bookingId}")
    public BookingView get(@RequestHeader("X-User-Id") Long userId, @PathVariable Long bookingId) {
        return service.get(userId, bookingId);
    }
}
