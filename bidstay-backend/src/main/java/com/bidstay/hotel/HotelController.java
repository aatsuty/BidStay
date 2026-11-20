package com.bidstay.hotel;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hotels")
@RequiredArgsConstructor
public class HotelController {

    private final RoomTypeRepository roomTypes;

    /** Step 4 of the guest flow: list hotels the guest can choose to send a bid to. */
    @GetMapping
    public List<HotelSummary> list(@RequestParam String city,
                                   @RequestParam(defaultValue = "STANDARD") RoomCategory category) {
        return roomTypes.findSummaries(city, HotelStatus.ACTIVE, category);
    }
}
