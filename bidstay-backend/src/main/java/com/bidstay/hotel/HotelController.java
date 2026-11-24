package com.bidstay.hotel;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/hotels")
@RequiredArgsConstructor
public class HotelController {

    private final RoomTypeRepository roomTypes;
    private final HotelSearch search;

    @GetMapping("/search")
    public HotelSearch.Page search(@RequestParam String city,
                                   @RequestParam LocalDate checkIn, @RequestParam LocalDate checkOut,
                                   @RequestParam(defaultValue = "STANDARD") RoomCategory category,
                                   @RequestParam(defaultValue = "1") int rooms,
                                   @RequestParam(defaultValue = "2") int guests,
                                   @RequestParam(defaultValue = "0") long after) {
        return search.available(city, checkIn, checkOut, category, rooms, guests, after);
    }

    @GetMapping("/{hotelId}")
    public HotelSearch.Details details(@PathVariable long hotelId) {
        return search.details(hotelId);
    }

    /** Step 4 of the guest flow: list hotels the guest can choose to send a bid to. */
    @GetMapping
    public List<HotelSummary> list(@RequestParam String city,
                                   @RequestParam(defaultValue = "STANDARD") RoomCategory category) {
        return roomTypes.findSummaries(city, HotelStatus.ACTIVE, category);
    }
}
