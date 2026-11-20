package com.bidstay.bidding;

import com.bidstay.booking.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Timers are driven from the database (not memory), so they survive restarts.
 * Each step is isolated: one failing step must not block the others.
 * TODO before running 2+ instances: add ShedLock so only one node runs these.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiryJobs {

    private final BidService bidService;
    private final BookingService bookingService;

    @Scheduled(fixedDelayString = "PT30S")
    public void tick() {
        run("expire offers", bidService::expireOffers);
        run("expire bids", bidService::expireBids);
        run("release payment holds", bookingService::releaseExpiredHolds);
    }

    private void run(String name, java.util.function.IntSupplier step) {
        try {
            int n = step.getAsInt();
            if (n > 0) log.info("{}: {} item(s)", name, n);
        } catch (Exception e) {
            // Optimistic-lock clashes with a hotel reply are expected and simply retried next tick.
            log.warn("{} failed, will retry: {}", name, e.toString());
        }
    }
}
