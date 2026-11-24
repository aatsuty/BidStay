package com.bidstay.admin;

import com.bidstay.audit.AuditTrail;
import com.bidstay.audit.AuditTrail.Action;
import com.bidstay.admin.AdminDtos.*;
import com.bidstay.common.ApiException;
import com.bidstay.hotel.*;
import com.bidstay.partner.PartnerChatService;
import com.bidstay.partner.PartnerMessage;
import com.bidstay.partner.PartnerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final HotelRepository hotels;
    private final HotelDocumentRepository docs;
    private final RoomTypeRepository roomTypes;
    private final PartnerService partner;
    private final PartnerChatService chat;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditTrail audit;

    // ------------------------------------------------------------------ overview

    @Transactional(readOnly = true)
    public Stats stats() {
        long live = count("select count(*) from hotels where status = 'ACTIVE'");
        long pending = count("select count(*) from hotels where status = 'PENDING_VERIFICATION'");
        long bids = count("select count(*) from bid_requests");
        long bidsWithOffer = count("select count(distinct bid_request_id) from bid_offers where status in ('ACCEPTED','COUNTERED','SELECTED')");
        long paid = count("select count(*) from bookings where status = 'CONFIRMED'");
        BigDecimal gmv = jdbc.queryForObject("select coalesce(sum(total_amount),0) from bookings where status = 'CONFIRMED'", BigDecimal.class);
        BigDecimal commission = jdbc.queryForObject("select coalesce(sum(commission_amount),0) from bookings where status = 'CONFIRMED'", BigDecimal.class);

        // Offers a human had to answer (not decided by rules), excluding ones the guest closed before the hotel replied.
        long needed = count("""
                select count(*) from bid_offers
                where auto_accepted = false and status <> 'UNAVAILABLE'
                  and not (status = 'CANCELLED' and responded_at is null)
                  and not (status = 'PENDING' and expires_at > now())
                """);
        long inTime = count("""
                select count(*) from bid_offers
                where auto_accepted = false and responded_at is not null and responded_at <= created_at + interval '15 minutes'
                """);

        return new Stats(live, pending, bids, paid, gmv, commission, List.of(
                new Target("accepted", "Bids that got an offer", pct(bidsWithOffer, bids), 30, bidsWithOffer + " of " + bids + " bids"),
                new Target("reply", "Hotel replies within 15 minutes", pct(inTime, needed), 60, inTime + " of " + needed + " requests that needed a reply"),
                new Target("paid", "Bids ending in a paid booking", pct(paid, bids), 15, paid + " of " + bids + " bids")));
    }

    // ------------------------------------------------------------------ hotel approval

    @Transactional(readOnly = true)
    public List<HotelRow> hotels(HotelStatus status) {
        List<Hotel> list = status == null ? hotels.findAll() : hotels.findAll().stream().filter(h -> h.getStatus() == status).toList();
        return list.stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .map(this::row).toList();
    }

    @Transactional(readOnly = true)
    public HotelPage hotelsPage(HotelStatus status, long before) {
        int size = 24;
        var page = hotels.adminPage(status, before, PageRequest.of(0, size + 1));
        var rows = page.stream().limit(size).map(this::row).toList();
        return new HotelPage(rows, page.size() > size ? page.get(size - 1).getId() : null);
    }

    public record HotelPage(List<HotelRow> hotels, Long nextCursor) {}

    @Transactional
    public HotelRow verifyDocument(Long hotelId, DocType type, boolean verified, Long versionId, Long reviewerId) {
        Hotel hotel = lockedHotel(hotelId);
        HotelDocument d = docs.findByHotelIdAndDocType(hotelId, type)
                .orElseThrow(() -> ApiException.invalid("The hotel has not submitted this document yet"));
        if (!Objects.equals(d.getVersionId(), versionId)) {
            throw ApiException.conflict("The document was replaced. Review the latest file.");
        }
        if (versionId != null) {
            jdbc.update("insert into hotel_document_reviews (version_id, reviewed_by, verified) values (?, ?, ?)",
                    versionId, reviewerId, verified);
        }
        if (!verified && hotel.getStatus() == HotelStatus.ACTIVE) hotel.setStatus(HotelStatus.PENDING_VERIFICATION);
        d.setVerified(verified);
        d.setVerifiedAt(verified ? clock.instant() : null);
        audit.record(reviewerId, verified ? Action.DOCUMENT_VERIFIED : Action.DOCUMENT_UNVERIFIED, d.getId(), hotelId);
        return row(hotel);
    }

    @Transactional
    public HotelRow approve(Long actorId, Long hotelId) {
        Hotel h = lockedHotel(hotelId);
        long verified = docs.findByHotelId(hotelId).stream().filter(HotelDocument::isVerified).count();
        if (verified < DocType.values().length) throw ApiException.invalid("Verify all four documents before approving");
        h.setStatus(HotelStatus.ACTIVE);
        h.setRejectionReason(null);
        audit.record(actorId, Action.HOTEL_APPROVED, hotelId, hotelId);
        chat.bot(hotelId, PartnerMessage.Kind.SYSTEM,
                "*Congratulations.* " + h.getName() + " is verified and now live on BidStay. You will receive bid requests here.", null);
        return row(h);
    }

    @Transactional
    public HotelRow reject(Long actorId, Long hotelId, String reason) {
        Hotel h = lockedHotel(hotelId);
        h.setStatus(HotelStatus.REJECTED);
        h.setRejectionReason(reason.trim());
        audit.record(actorId, Action.HOTEL_REJECTED, hotelId, hotelId);
        chat.bot(hotelId, PartnerMessage.Kind.SYSTEM,
                "Your registration for " + h.getName() + " was not approved: " + reason.trim() + ". Please fix it and resubmit the document.", null);
        return row(h);
    }

    @Transactional
    public HotelRow setSuspended(Long actorId, Long hotelId, boolean suspended) {
        Hotel h = lockedHotel(hotelId);
        if (suspended && h.getStatus() != HotelStatus.ACTIVE) throw ApiException.invalid("Only live hotels can be suspended");
        if (!suspended && h.getStatus() != HotelStatus.SUSPENDED) throw ApiException.invalid("This hotel is not suspended");
        h.setStatus(suspended ? HotelStatus.SUSPENDED : HotelStatus.ACTIVE);
        audit.record(actorId, suspended ? Action.HOTEL_SUSPENDED : Action.HOTEL_REINSTATED, hotelId, hotelId);
        return row(h);
    }

    // ------------------------------------------------------------------ monitoring

    @Transactional(readOnly = true)
    public List<BookingRow> bookings() {
        return jdbc.query("""
                select b.id, h.name, u.name, b.status, b.check_in, b.check_out, b.rooms, b.total_amount, b.commission_amount, b.created_at
                from bookings b join hotels h on h.id = b.hotel_id join users u on u.id = b.guest_id
                order by b.id desc limit 100
                """, (rs, i) -> new BookingRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getDate(5).toLocalDate(), rs.getDate(6).toLocalDate(), rs.getInt(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                rs.getTimestamp(10).toInstant()));
    }

    @Transactional(readOnly = true)
    public List<BidRow> bids() {
        return jdbc.query("""
                select r.id, u.name, r.category, r.check_in, r.check_out, r.rooms, r.price_per_night, r.round, r.status,
                       (select count(*) from bid_offers o where o.bid_request_id = r.id and o.status <> 'UNAVAILABLE'),
                       (select count(*) from bid_offers o where o.bid_request_id = r.id and o.status in ('ACCEPTED','COUNTERED')),
                       r.created_at
                from bid_requests r join users u on u.id = r.guest_id
                order by r.id desc limit 100
                """, (rs, i) -> new BidRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDate(4).toLocalDate(),
                rs.getDate(5).toLocalDate(), rs.getInt(6), rs.getBigDecimal(7), rs.getInt(8), rs.getString(9),
                rs.getLong(10), rs.getLong(11), rs.getTimestamp(12).toInstant()));
    }

    // ------------------------------------------------------------------ helpers

    private Hotel lockedHotel(Long id) {
        return hotels.lockById(id).orElseThrow(() -> ApiException.notFound("Hotel"));
    }

    private HotelRow row(Hotel h) {
        List<com.bidstay.partner.PartnerDtos.DocumentView> docViews = partner.documents(h.getId());
        boolean all = docViews.stream().allMatch(com.bidstay.partner.PartnerDtos.DocumentView::verified);
        return new HotelRow(h.getId(), h.getName(), h.getCity(), h.getArea(), h.getStatus(), h.getRejectionReason(),
                h.getContactPhone(), h.getGstNumber(), roomTypes.findByHotelIdOrderById(h.getId()).size(),
                h.getCreatedAt(), all, docViews);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static Double pct(long part, long whole) {
        if (whole == 0) return null;
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
