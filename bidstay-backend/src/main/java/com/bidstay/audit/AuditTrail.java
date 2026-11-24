package com.bidstay.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditTrail {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long actorId, Action action, long subjectId, Long hotelId) {
        jdbc.update("""
                insert into audit_events(actor_id, action, subject_type, subject_id, hotel_id)
                values (?, ?, ?, ?, ?)
                """, actorId, action.name(), action.subject.name(), subjectId, hotelId);
    }

    public Page recent(long before) {
        var rows = jdbc.query("""
                select e.id,e.action,e.subject_type,e.subject_id,e.hotel_id,e.created_at,
                       e.actor_id,u.name
                from audit_events e left join users u on e.actor_id = u.id
                where e.id < ? order by e.id desc limit 51
                """, (rs, row) -> new Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getObject(5, Long.class), rs.getTimestamp(6).toInstant(), rs.getObject(7, Long.class), rs.getString(8)), before);
        return rows.size() > 50 ? new Page(rows.subList(0, 50), rows.get(49).id()) : new Page(rows, null);
    }

    public enum Action {
        INVITE_CREATED(Subject.INVITATION), INVITE_REVOKED(Subject.INVITATION), INVITE_ACCEPTED(Subject.INVITATION),
        STAFF_REMOVED(Subject.USER), ADMIN_SIGNED_IN(Subject.USER),
        HOTEL_APPROVED(Subject.HOTEL), HOTEL_REJECTED(Subject.HOTEL), HOTEL_SUSPENDED(Subject.HOTEL), HOTEL_REINSTATED(Subject.HOTEL),
        DOCUMENT_VERIFIED(Subject.DOCUMENT), DOCUMENT_UNVERIFIED(Subject.DOCUMENT),
        LISTING_PHOTO_APPROVED(Subject.PHOTO), LISTING_PHOTO_REJECTED(Subject.PHOTO), LISTING_PHOTO_PUBLISHED(Subject.PHOTO),
        BOOKING_CANCELLED(Subject.BOOKING), REFUND_QUEUED(Subject.BOOKING), REFUND_SUBMITTED(Subject.BOOKING),
        REFUND_PROCESSED(Subject.BOOKING), REFUND_NEEDS_REVIEW(Subject.BOOKING);

        private final Subject subject;

        Action(Subject subject) { this.subject = subject; }
    }

    private enum Subject { INVITATION, USER, HOTEL, DOCUMENT, PHOTO, BOOKING }

    public record Event(long id, String action, String subjectType, long subjectId, Long hotelId, Instant at,
                        Long actorId, String actorName) {}
    public record Page(List<Event> events, Long nextCursor) {}
}
