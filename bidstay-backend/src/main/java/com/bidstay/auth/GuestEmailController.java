package com.bidstay.auth;

import com.bidstay.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth/guest")
@RequiredArgsConstructor
class GuestEmailController {
    private final GuestEmailAuth auth;
    private final AuthService sessions;

    record Methods(boolean email) {}
    record LinkedEmail(String email, boolean canLink) {}
    record Challenge(UUID challengeId) {}
    record SendCode(@NotBlank @Email @Size(max = 254) String email) {}
    record VerifyCode(@NotBlank @Email @Size(max = 254) String email,
                      @NotNull UUID challengeId,
                      @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
                      @Size(max = 100) String name) {}

    @GetMapping("/methods")
    Methods methods() {
        return new Methods(auth.enabled());
    }

    @PostMapping("/email/code")
    ResponseEntity<Challenge> sendCode(@Valid @RequestBody SendCode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Challenge(auth.sendCode(body.email(), null)));
    }

    @PostMapping("/email/verify")
    ResponseEntity<AuthService.SignedIn> verifyCode(@Valid @RequestBody VerifyCode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(auth.signIn(body.email(), body.challengeId(), body.code(), body.name()));
    }

    @GetMapping("/email/link")
    ResponseEntity<LinkedEmail> linkedEmail(@CallerId(guestOnly = true, verifiedOnly = true) Long guestId,
                                           @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new LinkedEmail(auth.linkedEmail(guestId), sessions.hasRecentGuestSms(authorization, guestId)));
    }

    @PostMapping("/email/link/code")
    ResponseEntity<Challenge> sendLinkCode(@CallerId(guestOnly = true, verifiedOnly = true) Long guestId,
                                          @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                          @Valid @RequestBody SendCode body) {
        requireRecentSms(authorization, guestId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Challenge(auth.sendCode(body.email(), guestId)));
    }

    @PostMapping("/email/link/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyLink(@CallerId(guestOnly = true, verifiedOnly = true) Long guestId,
                    @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @Valid @RequestBody VerifyCode body) {
        requireRecentSms(authorization, guestId);
        auth.link(body.email(), body.challengeId(), body.code(), guestId);
    }

    private void requireRecentSms(String authorization, long guestId) {
        if (!sessions.hasRecentGuestSms(authorization, guestId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Sign in again with your mobile number before linking an email.");
        }
    }
}
