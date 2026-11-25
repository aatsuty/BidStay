package com.bidstay.user;

import com.bidstay.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** Centralizes role and property membership checks for authenticated callers. */
@Service
@RequiredArgsConstructor
public class AccessService {

    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public User user(Long userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }

    public User requireAdmin(Long userId) {
        User u = user(userId);
        if (u.getRole() != Role.ADMIN) throw new ApiException(HttpStatus.FORBIDDEN, "Admins only");
        return u;
    }

    /** Hotel staff of that hotel, or an admin (support). Anyone else is refused. */
    public User requireHotelAccess(Long userId, Long hotelId) {
        User u = user(userId);
        if (u.getRole() == Role.ADMIN) return u;
        if (u.getRole() == Role.HOTEL_STAFF && hotelIdsOf(userId).contains(hotelId)) return u;
        throw new ApiException(HttpStatus.FORBIDDEN, "You do not have access to this hotel");
    }

    public User requireStaffManager(Long userId, Long hotelId) {
        User actor = user(userId);
        if (actor.getRole() == Role.ADMIN) return actor;
        if (actor.getRole() == Role.HOTEL_STAFF && canManageMembership(userId, hotelId)) return actor;
        throw new ApiException(HttpStatus.FORBIDDEN, "Only property managers can do that");
    }

    private boolean canManageMembership(Long userId, Long hotelId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from hotel_staff where user_id = ? and hotel_id = ? and can_manage_staff)
                """, Boolean.class, userId, hotelId));
    }

    public boolean hasHotelMembership(Long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from hotel_staff where user_id = ?)",
                Boolean.class, userId));
    }

    public User requireHotelStaff(Long userId) {
        User u = user(userId);
        if (u.getRole() != Role.HOTEL_STAFF) throw new ApiException(HttpStatus.FORBIDDEN, "Hotel partners only");
        return u;
    }

    public List<Long> hotelIdsOf(Long userId) {
        return jdbc.queryForList("select hotel_id from hotel_staff where user_id = ?", Long.class, userId);
    }
}
