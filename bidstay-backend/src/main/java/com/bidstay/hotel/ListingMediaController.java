package com.bidstay.hotel;

import com.bidstay.auth.CallerId;
import com.bidstay.common.ApiException;
import com.bidstay.user.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class ListingMediaController {
    private final ListingMedia media;
    private final AccessService access;
    @Value("${bidstay.media.edge-ttl-seconds:0}") private int edgeTtl;

    @GetMapping("/media/{id}/{size}")
    ResponseEntity<byte[]> publicImage(@PathVariable long id, @PathVariable String size) {
        if (!"thumb".equals(size) && !"full".equals(size)) throw ApiException.notFound("Photo");
        CacheControl caching = edgeTtl > 0
                ? CacheControl.maxAge(Duration.ZERO).sMaxAge(Duration.ofSeconds(Math.min(edgeTtl, 300))).mustRevalidate().cachePublic()
                : CacheControl.noCache().cachePublic();
        return image(media.publicImage(id, "thumb".equals(size)), caching);
    }

    @GetMapping("/partner/hotels/{hotelId}/media")
    ListingMedia.PartnerGallery partnerGallery(@CallerId Long userId, @PathVariable long hotelId) {
        access.requireHotelAccess(userId, hotelId);
        return media.gallery(hotelId);
    }

    @PostMapping(value = "/partner/hotels/{hotelId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    void upload(@CallerId Long userId, @PathVariable long hotelId, @RequestParam MultipartFile file,
                @RequestParam(required = false) Long roomTypeId, @RequestParam(required = false) String caption) throws IOException {
        access.requireHotelAccess(userId, hotelId);
        media.upload(userId, hotelId, roomTypeId, caption, file);
    }

    @GetMapping("/partner/hotels/{hotelId}/media/{id}/preview")
    ResponseEntity<byte[]> partnerPreview(@CallerId Long userId, @PathVariable long hotelId, @PathVariable long id) {
        access.requireHotelAccess(userId, hotelId);
        return image(media.privateImage(hotelId, id), CacheControl.noStore());
    }

    @PostMapping("/partner/hotels/{hotelId}/media/{id}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void withdraw(@CallerId Long userId, @PathVariable long hotelId, @PathVariable long id) {
        access.requireHotelAccess(userId, hotelId);
        media.withdraw(hotelId, id);
    }

    @GetMapping("/admin/media")
    ListingMedia.PhotoPage queue(@CallerId Long userId, @RequestParam(defaultValue = "9223372036854775807") long before) {
        access.requireAdmin(userId);
        return media.queue(before);
    }

    @GetMapping("/admin/media/{id}/preview")
    ResponseEntity<byte[]> adminPreview(@CallerId Long userId, @PathVariable long id) {
        access.requireAdmin(userId);
        return image(media.privateImage(null, id), CacheControl.noStore());
    }

    @PostMapping("/admin/media/{id}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void review(@CallerId Long userId, @PathVariable long id, @RequestBody Decision action) {
        access.requireAdmin(userId);
        if (action.approved() == null) throw ApiException.invalid("Choose a review decision");
        media.review(userId, id, action.approved(), action.reason());
    }

    @PostMapping("/admin/hotels/{hotelId}/documents/PHOTOS/publish")
    @ResponseStatus(HttpStatus.CREATED)
    void publishDocumentPhoto(@CallerId Long userId, @PathVariable long hotelId, @RequestParam long versionId) {
        access.requireAdmin(userId);
        media.publishDocumentPhoto(userId, hotelId, versionId);
    }

    private static ResponseEntity<byte[]> image(byte[] body, CacheControl caching) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(caching)
                .header("X-Content-Type-Options", "nosniff").body(body);
    }

    record Decision(Boolean approved, String reason) {}
}
