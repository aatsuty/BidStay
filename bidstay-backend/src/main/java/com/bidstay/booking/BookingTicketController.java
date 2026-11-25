package com.bidstay.booking;

import com.bidstay.auth.CallerId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class BookingTicketController {
    private final BookingService bookings;
    private final BookingTickets tickets;
    private final TicketContacts contacts;

    @GetMapping("/bookings/{bookingId}/ticket")
    ResponseEntity<String> ticket(@CallerId(guestOnly = true) Long userId, @PathVariable Long bookingId) {
        String html = tickets.render(userId, bookingId, null);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'self'")
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(html);
    }

    @GetMapping("/bookings/{bookingId}/ticket/delivery")
    Delivery delivery(@CallerId(guestOnly = true) Long userId, @PathVariable Long bookingId) {
        var ticket = bookings.ticket(userId, bookingId);
        boolean confirmed = ticket.booking().status() == BookingStatus.CONFIRMED;
        return new Delivery(bookings.ticketDelivery(userId, bookingId),
                confirmed ? ticket.hotelPhone() : null, ticket.hotelId(), confirmed, ticket.booking().receipt().demo());
    }

    @PostMapping("/bookings/{bookingId}/ticket/delivery")
    ResponseEntity<Void> deliver(@CallerId(verifiedOnly = true, guestOnly = true) Long userId, @PathVariable Long bookingId) {
        bookings.deliverTicket(userId, bookingId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/guest/ticket-contacts")
    TicketContacts.Contact contact(@CallerId(verifiedOnly = true, guestOnly = true) Long userId) {
        return contacts.contact(userId);
    }

    @PutMapping("/guest/ticket-contacts/sms")
    TicketContacts.Contact sms(@CallerId(verifiedOnly = true, guestOnly = true) Long userId, @RequestBody Sms preference) {
        return contacts.setSms(userId, preference.enabled());
    }

    @PostMapping("/auth/guest/ticket-email/code")
    ResponseEntity<Void> code(@CallerId(verifiedOnly = true, guestOnly = true) Long userId, @Valid @RequestBody Address address) {
        contacts.sendEmailCode(userId, address.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/guest/ticket-email/verify")
    TicketContacts.Contact verify(@CallerId(verifiedOnly = true, guestOnly = true) Long userId, @Valid @RequestBody Code code) {
        return contacts.verifyEmail(userId, code.code());
    }

    @DeleteMapping("/guest/ticket-contacts/email")
    TicketContacts.Contact removeEmail(@CallerId(verifiedOnly = true, guestOnly = true) Long userId) {
        return contacts.removeEmail(userId);
    }

    record Sms(boolean enabled) {}
    record Address(@NotBlank @Email @Size(max = 254) String email) {}
    record Code(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
    record Delivery(List<BookingTicketDelivery.DeliveryStatus> channels, String hotelPhone, long hotelId, boolean confirmed, boolean demo) {}
}
