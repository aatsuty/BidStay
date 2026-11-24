package com.bidstay.auth;

import com.bidstay.user.AccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class StaffAuthController {
    private final AccessService access;
    private final AuthService auth;
    private final StaffSignIn staff;
    private final AdminSignIn admin;

    @PostMapping("/partner/hotels/{hotelId}/invites")
    ResponseEntity<StaffSignIn.Invitation> invite(@CallerId Long caller, @PathVariable Long hotelId,
                                                 @Valid @RequestBody AuthController.SendCode body) {
        access.requireHotelAccess(caller, hotelId);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(staff.invite(caller, hotelId, body.phone()));
    }

    @PostMapping("/auth/staff/code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendCode(@Valid @RequestBody AuthController.SendCode body) {
        auth.sendCode(body.phone());
    }

    @PostMapping("/auth/staff/verify")
    ResponseEntity<AuthService.SignedIn> verify(@Valid @RequestBody VerifyStaff body) {
        var session = staff.verify(body.phone(), body.code(), body.name(), body.invite());
        if (session == null) throw StaffSignIn.rejected();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session);
    }

    @PostMapping("/auth/admin/sign-in")
    ResponseEntity<AuthService.SignedIn> admin(@Valid @RequestBody AdminCredentials body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(admin.signIn(body.phone(), body.password(), body.code()));
    }

    record VerifyStaff(@NotBlank @Pattern(regexp = "[6-9][0-9]{9}") String phone,
                       @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
                       @Size(max = 100) String name,
                       @Pattern(regexp = "[A-Za-z0-9_-]{43}") String invite) {}

    record AdminCredentials(@NotBlank @Pattern(regexp = "[6-9][0-9]{9}") String phone,
                            @NotBlank @Size(max = 72) String password,
                            @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
}
