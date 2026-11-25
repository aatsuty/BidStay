package com.bidstay.auth;

import com.bidstay.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
class AuthController {
    private final AuthService auth;

    record SendCode(@NotBlank @Pattern(regexp = "[6-9][0-9]{9}") String phone) {}
    record VerifyCode(@NotBlank @Pattern(regexp = "[6-9][0-9]{9}") String phone,
                      @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
                      @Size(max = 100) String name) {}

    @PostMapping("/guest/code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendCode(@Valid @RequestBody SendCode body) {
        auth.sendCode(body.phone());
    }

    @PostMapping("/guest/verify")
    ResponseEntity<AuthService.SignedIn> verifyCode(@Valid @RequestBody VerifyCode body) {
        var session = auth.verifyCode(body.phone(), body.code(), body.name());
        if (session == null) throw new ApiException(HttpStatus.FORBIDDEN, "This account uses a staff sign-in.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        auth.signOut(authorization);
    }
}
