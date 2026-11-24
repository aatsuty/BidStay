package com.bidstay.booking;

import com.bidstay.audit.AuditTrail;
import com.bidstay.common.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static com.bidstay.inventory.InventoryService.STAY_ZONE;

@Service
@RequiredArgsConstructor
public class BookingReviews {
    private final BookingRepository bookings;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditTrail audit;

    private boolean completed(Booking booking) {
        if (booking.getStatus() != BookingStatus.CONFIRMED || booking.getPaymentRef() == null) return false;
        Instant departure = booking.getDepartureAt() != null ? booking.getDepartureAt()
                : booking.getCheckOut().plusDays(1).atStartOfDay(STAY_ZONE).toInstant();
        return !departure.isAfter(clock.instant());
    }

    String statusFor(Booking booking) {
        if (!completed(booking)) return null;
        return jdbc.query("select status from booking_reviews where booking_id = ?",
                (rs, row) -> rs.getString(1), booking.getId()).stream().findFirst().orElse("ELIGIBLE");
    }

    @Transactional
    public void submit(Long guestId, Long bookingId, ReviewInput input) {
        Booking booking = bookings.lockById(bookingId).filter(b -> b.getGuestId().equals(guestId))
                .orElseThrow(() -> ApiException.notFound("Booking"));
        if (!completed(booking)) throw ApiException.conflict("You can review the hotel after your paid stay ends");
        String comment = input.comment() == null || input.comment().isBlank() ? null : input.comment().strip();
        if (jdbc.update("""
                insert into booking_reviews (booking_id, hotel_id, rating, comment)
                values (?, ?, ?, ?) on conflict (booking_id) do nothing
                """, bookingId, booking.getHotelId(), input.rating(), comment) == 0) {
            throw ApiException.conflict("You already reviewed this booking");
        }
    }

    @Transactional(readOnly = true)
    public List<PublicReview> published(Long hotelId) {
        return jdbc.query("""
                select r.rating, r.comment, r.created_at
                from booking_reviews r join hotels h on h.id = r.hotel_id
                where r.hotel_id = ? and r.status = 'PUBLISHED' and h.status = 'ACTIVE'
                order by r.booking_id desc limit 20
                """, (rs, row) -> new PublicReview(rs.getInt(1), rs.getString(2), rs.getTimestamp(3).toInstant()), hotelId);
    }

    @Transactional(readOnly = true)
    public List<AdminReview> adminQueue(ReviewStatus status, long before) {
        return jdbc.query("""
                select r.booking_id, h.name, r.rating, r.comment, r.created_at
                from booking_reviews r join hotels h on h.id = r.hotel_id
                where r.status = ? and r.booking_id < ?
                order by r.booking_id desc limit 50
                """, (rs, row) -> new AdminReview(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                rs.getTimestamp(5).toInstant()), status.name(), before);
    }

    @Transactional
    public void moderate(Long actor, Long bookingId, ReviewStatus status) {
        if (status == ReviewStatus.PENDING) throw ApiException.invalid("Choose publish or hide");
        Long hotelId = jdbc.query("""
                update booking_reviews set status = ? where booking_id = ? returning hotel_id
                """, (rs, row) -> rs.getLong(1), status.name(), bookingId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Review"));
        audit.record(actor, status == ReviewStatus.PUBLISHED ? AuditTrail.Action.REVIEW_PUBLISHED
                : AuditTrail.Action.REVIEW_HIDDEN, bookingId, hotelId);
    }

    public enum ReviewStatus { PENDING, PUBLISHED, HIDDEN }
    public record ReviewInput(@Min(1) @Max(5) int rating, @Size(max = 500) String comment) {}
    public record PublicReview(int rating, String comment, Instant createdAt) {}
    public record AdminReview(long bookingId, String hotelName, int rating, String comment, Instant createdAt) {}
}
