package com.bidstay.admin;

import com.bidstay.auth.CallerId;
import com.bidstay.hotel.HotelDocumentFiles;
import com.bidstay.common.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.bidstay.admin.AdminDtos.*;
import com.bidstay.hotel.DocType;
import com.bidstay.hotel.HotelStatus;
import com.bidstay.user.AccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin portal API. Every route requires the ADMIN role. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService service;
    private final AccessService access;
    private final HotelDocumentFiles files;

    @GetMapping("/stats")
    public Stats stats(@CallerId Long userId) {
        access.requireAdmin(userId);
        return service.stats();
    }

    @GetMapping("/hotels")
    public List<HotelRow> hotels(@CallerId Long userId, @RequestParam(required = false) HotelStatus status) {
        access.requireAdmin(userId);
        return service.hotels(status);
    }

    @GetMapping("/hotels/page")
    public AdminService.HotelPage hotelsPage(@CallerId Long userId, @RequestParam(required = false) HotelStatus status,
                                             @RequestParam(defaultValue = "9223372036854775807") long before) {
        access.requireAdmin(userId);
        return service.hotelsPage(status, before);
    }

    @PostMapping("/hotels/{hotelId}/documents/{type}/{decision}")
    public HotelRow verifyDocument(@CallerId Long userId, @PathVariable Long hotelId,
                                   @PathVariable DocType type, @PathVariable String decision,
                                   @RequestParam(required = false) Long versionId) {
        access.requireAdmin(userId);
        if (!List.of("verify", "unverify").contains(decision)) throw ApiException.invalid("Unknown review decision");
        return service.verifyDocument(hotelId, type, "verify".equals(decision), versionId, userId);
    }

    @GetMapping("/hotels/{hotelId}/documents/{type}/files/{versionId}")
    public ResponseEntity<byte[]> download(@CallerId Long userId, @PathVariable Long hotelId,
                                           @PathVariable DocType type, @PathVariable Long versionId) {
        access.requireAdmin(userId);
        var file = files.download(hotelId, type, versionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.name()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }

    @PostMapping("/hotels/{hotelId}/approve")
    public HotelRow approve(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireAdmin(userId);
        return service.approve(userId, hotelId);
    }

    @PostMapping("/hotels/{hotelId}/reject")
    public HotelRow reject(@CallerId Long userId, @PathVariable Long hotelId, @Valid @RequestBody Reject body) {
        access.requireAdmin(userId);
        return service.reject(userId, hotelId, body.reason());
    }

    @PostMapping("/hotels/{hotelId}/suspend")
    public HotelRow suspend(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireAdmin(userId);
        return service.setSuspended(userId, hotelId, true);
    }

    @PostMapping("/hotels/{hotelId}/reinstate")
    public HotelRow reinstate(@CallerId Long userId, @PathVariable Long hotelId) {
        access.requireAdmin(userId);
        return service.setSuspended(userId, hotelId, false);
    }

    @GetMapping("/bookings")
    public List<BookingRow> bookings(@CallerId Long userId) {
        access.requireAdmin(userId);
        return service.bookings();
    }

    @GetMapping("/bids")
    public List<BidRow> bids(@CallerId Long userId) {
        access.requireAdmin(userId);
        return service.bids();
    }
}
