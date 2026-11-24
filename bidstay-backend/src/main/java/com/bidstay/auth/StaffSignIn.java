package com.bidstay.auth;

import com.bidstay.audit.AuditTrail;
import com.bidstay.audit.AuditTrail.Action;
import com.bidstay.common.ApiException;
import com.bidstay.hotel.HotelRepository;
import com.bidstay.user.AccessService;
import com.bidstay.user.Role;
import com.bidstay.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
class StaffSignIn {
    private final AuthService auth;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final HotelRepository hotels;
    private final AccessService access;
    private final AuditTrail audit;

    @Transactional(readOnly = true)
    Team team(Long caller, Long hotelId) {
        var actor = access.requireStaffManager(caller, hotelId);
        boolean admin = actor.getRole() == Role.ADMIN;
        var members = jdbc.query("""
                select u.id, u.name, u.phone, s.can_manage_staff
                from hotel_staff s join users u on s.user_id = u.id
                where s.hotel_id = ? and u.role = 'HOTEL_STAFF' order by s.can_manage_staff desc, u.name, u.id
                """, (rs, row) -> {
            long id = rs.getLong(1);
            boolean manager = rs.getBoolean(4);
            return new Member(id, rs.getString(2), rs.getString(3), manager, id != caller && (admin || !manager));
        }, hotelId);
        var invitations = jdbc.query("""
                select id, phone, can_manage_staff, expires_at
                from staff_invites where hotel_id = ? and accepted_at is null and revoked_at is null and expires_at > ?
                order by id desc
                """, (rs, row) -> new PendingInvite(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                rs.getTimestamp(4).toInstant(), admin || !rs.getBoolean(3)), hotelId, Timestamp.from(clock.instant()));
        return new Team(members, invitations, admin);
    }

    @Transactional
    Invitation invite(Long userId, Long hotelId, String phone, boolean manager) {
        lockHotel(hotelId);
        var actor = access.requireStaffManager(userId, hotelId);
        if (manager && actor.getRole() != Role.ADMIN) throw new ApiException(HttpStatus.FORBIDDEN, "Admins can invite hotel managers");
        var existing = users.findByPhone(phone);
        if (existing.isPresent() && existing.get().getRole() != Role.HOTEL_STAFF) {
            throw ApiException.conflict("Use a phone number reserved for the hotel staff account");
        }

        var prior = jdbc.query("""
                select id, can_manage_staff from staff_invites
                where hotel_id = ? and phone = ? and accepted_at is null and revoked_at is null and expires_at > ?
                """, (rs, row) -> new PreviousInvite(rs.getLong(1), rs.getBoolean(2)), hotelId, phone, Timestamp.from(clock.instant()));
        if (actor.getRole() != Role.ADMIN && prior.stream().anyMatch(PreviousInvite::manager)) {
            throw ApiException.conflict("An admin invitation already exists for this phone");
        }
        prior.forEach(invite -> cancelInvite(userId, hotelId, invite.id()));

        String token = AuthService.newToken();
        Instant expiresAt = clock.instant().plus(Duration.ofHours(48));
        Long invitationId = jdbc.queryForObject("""
                insert into staff_invites (token_hash, hotel_id, phone, created_by, expires_at, can_manage_staff)
                values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, AuthService.hash(token), hotelId, phone, userId, Timestamp.from(expiresAt), manager);
        audit.record(userId, Action.INVITE_CREATED, invitationId, hotelId);
        return new Invitation(token, expiresAt);
    }

    @Transactional
    void revokeInvite(Long caller, Long hotelId, long invitationId) {
        lockHotel(hotelId);
        var actor = access.requireStaffManager(caller, hotelId);
        var invite = jdbc.query("""
                select can_manage_staff from staff_invites where hotel_id = ? and id = ?
                  and accepted_at is null and revoked_at is null and expires_at > ?
                """, (rs, row) -> rs.getBoolean(1), hotelId, invitationId, Timestamp.from(clock.instant()))
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Pending invitation"));
        if (invite && actor.getRole() != Role.ADMIN) throw new ApiException(HttpStatus.FORBIDDEN, "Admins can revoke manager invitations");
        cancelInvite(caller, hotelId, invitationId);
    }

    private void cancelInvite(Long caller, Long hotelId, long invitationId) {
        jdbc.update("update staff_invites set revoked_at = now(), revoked_by = ? where id = ?", caller, invitationId);
        audit.record(caller, Action.INVITE_REVOKED, invitationId, hotelId);
    }

    @Transactional
    void removeMember(Long caller, Long hotelId, Long staffId) {
        lockHotel(hotelId);
        var actor = access.requireStaffManager(caller, hotelId);
        if (caller.equals(staffId)) throw ApiException.conflict("Ask an admin to remove your own access");
        boolean manager = jdbc.query("select can_manage_staff from hotel_staff where hotel_id = ? and user_id = ?",
                (rs, row) -> rs.getBoolean(1), hotelId, staffId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Hotel staff member"));
        if (manager && actor.getRole() != Role.ADMIN) throw new ApiException(HttpStatus.FORBIDDEN, "Admins can remove hotel managers");

        String phone = jdbc.queryForObject("select phone from users where id = ? for update", String.class, staffId);
        jdbc.update("delete from hotel_staff where user_id = ? and hotel_id = ?", staffId, hotelId);
        jdbc.update("update whatsapp_consents set enabled = false, changed_at = now() where user_id = ? and hotel_id = ?", staffId, hotelId);
        var revokedInvites = jdbc.query("""
                update staff_invites set revoked_at = now(), revoked_by = ?
                where hotel_id = ? and accepted_at is null and revoked_at is null and expires_at > now()
                  and (created_by = ? or phone = ?) returning id
                """, (rs, row) -> rs.getLong(1), caller, hotelId, staffId, phone);
        revokedInvites.forEach(id -> audit.record(caller, Action.INVITE_REVOKED, id, hotelId));
        if (!access.hasHotelMembership(staffId)) jdbc.update("delete from auth_sessions where user_id = ?", staffId);
        audit.record(caller, Action.STAFF_REMOVED, staffId, hotelId);
    }

    AuthService.SignedIn verify(String phone, String code, String name, String inviteToken) {
        return auth.verifyPhone(phone, code, () -> {
            if (inviteToken != null) return accept(phone, name, inviteToken);
            return users.findByPhone(phone).filter(user -> user.getRole() == Role.HOTEL_STAFF)
                    .filter(user -> access.hasHotelMembership(user.getId()))
                    .map(auth::issueSession).orElse(null);
        });
    }

    private AuthService.SignedIn accept(String phone, String name, String inviteToken) {
        String tokenHash = AuthService.hash(inviteToken);
        Long hotelId = jdbc.query("select hotel_id from staff_invites where token_hash = ? and phone = ?",
                (rs, row) -> rs.getLong(1), tokenHash, phone).stream().findFirst().orElse(null);
        if (hotelId == null) return null;
        lockHotel(hotelId);
        var invitation = jdbc.query("""
                select id, created_by, can_manage_staff from staff_invites where token_hash = ? and phone = ?
                  and accepted_at is null and revoked_at is null and expires_at > ?
                """, (rs, row) -> new AcceptableInvite(rs.getLong(1), rs.getLong(2), rs.getBoolean(3)),
                tokenHash, phone, Timestamp.from(clock.instant())).stream().findFirst().orElse(null);
        if (invitation == null) return null;

        try {
            var issuer = access.requireStaffManager(invitation.issuer(), hotelId);
            if (invitation.manager() && issuer.getRole() != Role.ADMIN) return null;
        } catch (ApiException failure) {
            return null;
        }
        jdbc.update("""
                insert into users (phone, name, role) values (?, ?, 'HOTEL_STAFF')
                on conflict (phone) do nothing
                """, phone, AuthService.displayName(name, "Hotel partner"));
        var user = users.findByPhone(phone).orElseThrow();
        if (user.getRole() != Role.HOTEL_STAFF) return null;
        jdbc.queryForObject("select id from users where id = ? for update", Long.class, user.getId());
        jdbc.update("""
                insert into hotel_staff (user_id, hotel_id, can_manage_staff) values (?, ?, ?)
                on conflict (user_id, hotel_id) do update
                set can_manage_staff = hotel_staff.can_manage_staff or excluded.can_manage_staff
                """, user.getId(), hotelId, invitation.manager());
        jdbc.update("update staff_invites set accepted_at = ?, accepted_by = ? where id = ?",
                Timestamp.from(clock.instant()), user.getId(), invitation.id());
        audit.record(user.getId(), Action.INVITE_ACCEPTED, invitation.id(), hotelId);
        return auth.issueSession(user);
    }

    private void lockHotel(Long hotelId) {
        hotels.lockById(hotelId).orElseThrow(() -> ApiException.notFound("Hotel"));
    }

    static ApiException rejected() {
        return new ApiException(HttpStatus.FORBIDDEN, "This phone has no hotel staff access or the invitation is invalid");
    }

    private record PreviousInvite(long id, boolean manager) {}
    private record AcceptableInvite(long id, long issuer, boolean manager) {}
    record Invitation(String token, Instant expiresAt) {}
    record Member(long id, String name, String phone, boolean manager, boolean removable) {}
    record PendingInvite(long id, String phone, boolean manager, Instant expiresAt, boolean revocable) {}
    record Team(List<Member> members, List<PendingInvite> invitations, boolean canInviteManagers) {}
}
