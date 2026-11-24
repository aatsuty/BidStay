package com.bidstay.audit;

import com.bidstay.auth.CallerId;
import com.bidstay.user.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class AuditController {
    private final AccessService access;
    private final AuditTrail audit;

    @GetMapping("/api/v1/admin/audit")
    ResponseEntity<AuditTrail.Page> recent(@CallerId Long userId,
                                           @RequestParam(defaultValue = "9223372036854775807") long before) {
        access.requireAdmin(userId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(audit.recent(before));
    }
}
