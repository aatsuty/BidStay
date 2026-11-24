package com.bidstay.alerts;

import com.bidstay.auth.CallerId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/guest")
@RequiredArgsConstructor
class GuestAlertsController {
    private final GuestAlerts alerts;

    @GetMapping("/alerts")
    GuestAlerts.AlertPage inbox(@CallerId(guestOnly = true) Long guestId,
                                @RequestParam(defaultValue = "9223372036854775807") long before) {
        return alerts.inbox(guestId, before);
    }
    @GetMapping("/alerts/unread")
    Count unread(@CallerId(guestOnly = true) Long guestId) { return new Count(alerts.unread(guestId)); }
    @PostMapping("/alerts/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@CallerId(guestOnly = true) Long guestId, @RequestBody Read request) { alerts.markRead(guestId, request.alertId()); }

    @GetMapping("/saved-searches")
    List<GuestAlerts.Saved> searches(@CallerId(guestOnly = true) Long guestId) { return alerts.searches(guestId); }
    @PostMapping("/saved-searches")
    @ResponseStatus(HttpStatus.CREATED)
    GuestAlerts.Saved save(@CallerId(guestOnly = true) Long guestId, @Valid @RequestBody GuestAlerts.SaveSearch search) {
        return alerts.save(guestId, search);
    }
    @PutMapping("/saved-searches/{id}")
    GuestAlerts.Saved change(@CallerId(guestOnly = true) Long guestId, @PathVariable long id, @Valid @RequestBody Enabled body) {
        return alerts.setEnabled(guestId, id, body.enabled());
    }
    @DeleteMapping("/saved-searches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@CallerId(guestOnly = true) Long guestId, @PathVariable long id) { alerts.delete(guestId, id); }

    record Count(long count) {}
    record Read(Long alertId) {}
    record Enabled(@NotNull Boolean enabled) {}
}
