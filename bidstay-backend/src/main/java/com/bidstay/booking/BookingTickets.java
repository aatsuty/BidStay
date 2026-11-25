package com.bidstay.booking;

import com.bidstay.common.Money;
import com.bidstay.hotel.StayPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static com.bidstay.inventory.InventoryService.STAY_ZONE;

@Service
@RequiredArgsConstructor
class BookingTickets {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm 'IST'", Locale.ENGLISH).withZone(STAY_ZONE);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm 'IST'", Locale.ENGLISH);
    private final BookingService bookings;

    String render(long guestId, long bookingId, String url) {
        var details = bookings.ticket(guestId, bookingId);
        var booking = details.booking();
        var shortStay = booking.shortStay();
        StayPolicy policy = booking.stayPolicy();
        String checkIn = shortStay == null ? DATE.format(booking.checkIn()) : TIME.format(shortStay.arrival());
        String checkOut = shortStay == null ? DATE.format(booking.checkOut()) : TIME.format(shortStay.departure());
        String checkInTime = shortStay == null && policy != null && policy.checkInFrom() != null
                ? "From " + CLOCK.format(policy.checkInFrom()) : "";
        String checkOutTime = shortStay == null && policy != null && policy.checkOutBy() != null
                ? "By " + CLOCK.format(policy.checkOutBy()) : "";
        String duration = shortStay == null ? quantity(booking.nights(), "night") : quantity(shortStay.hours(), "hour");
        String rate = shortStay == null ? Money.inrExact(booking.pricePerNight()) + " per room / night"
                : Money.inrExact(shortStay.ratePerRoom()) + " per room / stay";
        var receipt = booking.receipt();
        String state = switch (booking.status()) {
            case CONFIRMED -> "Confirmed";
            case CANCELLED -> "Cancelled";
            default -> "Payment record";
        };
        String policyRows = row("Cancellation", booking.freeCancelUntil() == null
                        ? "Free cancellation unavailable after payment"
                        : "Free cancellation until " + TIME.format(booking.freeCancelUntil()))
                + optionalRow("Arrival instructions", policy == null ? null : policy.guestInstructions());
        String paymentRows = row("Rate", rate) + row("Payment reference", receipt.reference())
                + optionalRow("Payment recorded", formatTime(receipt.recordedAt()))
                + optionalRow("Refund status", refundStatus(booking.refundStatus()))
                + optionalRow("Refund reference", receipt.refundReference());
        String openLink = url == null ? "" : """
                <p style="margin:22px 0 0"><a style="background:#f7bd34;color:#172a4e;padding:12px 18px;display:inline-block;font-weight:bold;text-decoration:none;border-radius:8px"
                href="%s">View ticket in BidStay</a></p>
                """.formatted(escape(url));
        return """
                <!doctype html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>BidStay booking ticket #%d</title>
                  <style>
                    @page { size:A4; margin:12mm; }
                    @media print { body { print-color-adjust:exact; -webkit-print-color-adjust:exact; } }
                    @media screen and (max-width:420px) { .ticket-body { padding:16px !important; } }
                    .ticket-table td { overflow-wrap:anywhere; }
                  </style>
                </head>
                <body style="margin:0;padding:12px;background:#f1f5fd;color:#162746;font:15px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:690px;margin:0 auto;background:#fff;border:1px solid #dce5f5;border-radius:16px;border-collapse:separate;border-spacing:0">
                  <tr><td style="background:#142a52;color:#fff;padding:24px;border-radius:15px 15px 0 0;border-top:6px solid #f7bd34">
                    <span style="font-weight:800;font-size:22px">Bid<span style="color:#ffd365">Stay</span></span>
                    <h1 style="margin:8px 0 0;font-size:29px;line-height:1.15">Booking ticket</h1>
                    <p style="margin:7px 0 0;color:#dee9ff">Booking #%d · %s%s</p>
                  </td></tr>
                  <tr><td class="ticket-body" style="padding:22px">
                    <h2 style="margin:0;color:#152e58;font-size:24px">%s</h2>
                    <p style="margin:4px 0 16px;color:#536585">%s</p>
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#edf3fd;border-radius:10px;table-layout:fixed">
                      <tr>%s%s</tr>
                    </table>
                    <h3 style="font-size:16px;margin:19px 0 4px;color:#173462">Stay details</h3>
                    <table class="ticket-table" width="100%%" cellspacing="0" cellpadding="0" style="border-collapse:collapse;table-layout:fixed">%s</table>
                    <h3 style="font-size:16px;margin:19px 0 4px;color:#173462">Payment and refund</h3>
                    <table class="ticket-table" width="100%%" cellspacing="0" cellpadding="0" style="border-collapse:collapse;table-layout:fixed">%s</table>
                    <p style="background:#fff4d6;border-radius:8px;padding:12px;margin:14px 0 0;font-weight:750">%s <span style="font-size:20px;margin-left:10px">%s</span></p>
                    <h3 style="font-size:16px;margin:19px 0 4px;color:#173462">Stay terms</h3>
                    <table class="ticket-table" width="100%%" cellspacing="0" cellpadding="0" style="border-collapse:collapse;table-layout:fixed">%s</table>
                    %s
                  </td></tr>
                </table>
                </body>
                </html>
                """.formatted(bookingId, bookingId, state, receipt.demo() ? " · Demo payment" : "",
                escape(booking.hotelName()), escape(booking.hotelAddress()), dateBox("Check-in", checkIn, checkInTime),
                dateBox("Check-out", checkOut, checkOutTime),
                row("Guest", details.guestName()) + row("Room type", booking.roomName())
                        + row("Stay", quantity(booking.rooms(), "room") + " · "
                        + (details.guests() == null ? "" : quantity(details.guests(), "guest") + " · ") + duration)
                        + optionalRow("Hotel phone", details.hotelPhone() == null ? null : "+91 " + details.hotelPhone()),
                paymentRows, receipt.demo() ? "Demo total" : "Original amount paid", escape(Money.inrExact(booking.totalAmount())),
                policyRows, openLink);
    }

    private static String dateBox(String label, String date, String time) {
        return """
                <td style="padding:12px;vertical-align:top;width:50%%">
                <span style="display:block;color:#536585;font-size:12px">%s</span>
                <b style="display:block;color:#163364">%s</b>
                <span style="color:#536585;font-size:12px">%s</span></td>
                """.formatted(label, escape(date), escape(time));
    }

    private static String row(String label, String value) {
        return """
                <tr><th scope="row" style="width:37%%;text-align:left;vertical-align:top;color:#536585;font-weight:600;padding:5px 8px 5px 0;border-bottom:1px solid #e5ecf7">%s</th>
                <td style="padding:5px 0;border-bottom:1px solid #e5ecf7">%s</td></tr>
                """.formatted(label, escape(value).replace("\n", "<br>"));
    }

    private static String optionalRow(String label, String value) {
        return value == null || value.isBlank() ? "" : row(label, value);
    }

    private static String escape(String value) { return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8"); }
    private static String formatTime(Instant at) { return at == null ? null : TIME.format(at); }
    private static String quantity(long count, String unit) { return count + " " + unit + (count == 1 ? "" : "s"); }
    private static String refundStatus(String status) {
        if (status == null) return null;
        return switch (status) {
            case "REFUND_PENDING" -> "Refund pending";
            case "REFUND_SUBMITTED" -> "Refund submitted";
            case "REFUND_PROCESSED" -> "Refund processed";
            case "REFUND_REVIEW" -> "Refund under review";
            default -> status.replace('_', ' ').toLowerCase(Locale.ENGLISH);
        };
    }
}
