package com.bidstay.hotel;

import com.bidstay.booking.BookingReviews;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/hotels")
@RequiredArgsConstructor
public class HotelController {

    private final RoomTypeRepository roomTypes;
    private final HotelSearch search;
    private final HourlyStays hourly;
    private final BookingReviews reviews;

    @GetMapping("/destinations")
    public List<String> destinations(@RequestParam String prefix, @RequestParam(defaultValue = "false") boolean hourly) {
        return search.destinations(prefix, hourly);
    }

    @GetMapping("/places")
    public List<HotelSearch.Place> places(@RequestParam String prefix, @RequestParam(defaultValue = "false") boolean hourly) {
        return search.places(prefix, hourly);
    }

    @GetMapping("/{hotelId}/reviews")
    public List<BookingReviews.PublicReview> reviews(@PathVariable long hotelId) {
        return reviews.published(hotelId);
    }

    @GetMapping("/hourly")
    public HourlyStays.Page hourly(@RequestParam String city, @RequestParam LocalDate date,
                                    @RequestParam LocalTime arrival, @RequestParam int hours,
                                    @RequestParam(defaultValue = "STANDARD") RoomCategory category,
                                    @RequestParam(defaultValue = "1") int rooms,
                                    @RequestParam(defaultValue = "2") int guests,
                                    @RequestParam(defaultValue = "0") long after,
                                    @RequestParam(defaultValue = "") String area,
                                    @ModelAttribute MapSearchFilter map) {
        return hourly.search(city, date, arrival, hours, category, rooms, guests, after, area, map);
    }

    @GetMapping("/search")
    public HotelSearch.Page search(@RequestParam String city,
                                   @RequestParam LocalDate checkIn, @RequestParam LocalDate checkOut,
                                   @RequestParam(defaultValue = "STANDARD") RoomCategory category,
                                   @RequestParam(defaultValue = "1") int rooms,
                                   @RequestParam(defaultValue = "2") int guests,
                                   @RequestParam(defaultValue = "0") long after,
                                   @RequestParam(defaultValue = "") String area,
                                   @RequestParam(required = false) Integer minimumStars,
                                   @RequestParam(required = false) BigDecimal maximumRate,
                                   @ModelAttribute MapSearchFilter map) {
        return search.available(city, checkIn, checkOut, category, rooms, guests, after,
                new HotelSearch.Filters(area, minimumStars, maximumRate, map));
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
