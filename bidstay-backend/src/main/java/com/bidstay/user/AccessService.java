package com.bidstay.user;

import com.bidstay.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ONE place for "who is allowed to do this". Controllers pass the caller's id (today from the
 * X-User-Id dev header). When OTP/JWT login is added, only the way the id is obtained changes;
 * every rule below stays exactly as it is.
 */
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

    public User requireHotelStaff(Long userId) {
        User u = user(userId);
        if (u.getRole() != Role.HOTEL_STAFF) throw new ApiException(HttpStatus.FORBIDDEN, "Hotel partners only");
        return u;
    }

    public List<Long> hotelIdsOf(Long userId) {
        return jdbc.queryForList("select hotel_id from hotel_staff where user_id = ?", Long.class, userId);
    }
}
