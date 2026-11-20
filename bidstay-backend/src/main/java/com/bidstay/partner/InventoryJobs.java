package com.bidstay.partner;

import com.bidstay.hotel.HotelRepository;
import com.bidstay.hotel.HotelStatus;
import com.bidstay.hotel.RoomTypeRepository;
import com.bidstay.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** Keeps 90 days of bookable nights published for every live hotel. Runs at startup and daily. */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryJobs {

    static final int HORIZON_DAYS = 90;
    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final InventoryService inventory;
    private final Clock clock;

    @Scheduled(initialDelay = 20_000, fixedDelay = 6 * 60 * 60 * 1000)
    public void rollForward() {
        LocalDate from = LocalDate.now(clock);
        int added = 0;
        for (var h : hotels.findAll()) {
            if (h.getStatus() != HotelStatus.ACTIVE) continue;
            for (var rt : roomTypes.findByHotelIdOrderById(h.getId())) {
                added += inventory.ensureRange(rt.getId(), from, from.plusDays(HORIZON_DAYS), rt.getTotalRooms());
            }
        }
        if (added > 0) log.info("Published {} new room-nights", added);
    }
}
