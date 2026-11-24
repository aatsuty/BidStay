package com.bidstay.user;

import com.bidstay.auth.CallerId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells a portal who the caller is, so it can send them to the right place (guest / hotel / admin). */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    public record Me(Long id, String name, String phone, Role role) {}

    private final AccessService access;

    @GetMapping
    public Me me(@CallerId Long userId) {
        User u = access.user(userId);
        return new Me(u.getId(), u.getName(), u.getPhone(), u.getRole());
    }
}
