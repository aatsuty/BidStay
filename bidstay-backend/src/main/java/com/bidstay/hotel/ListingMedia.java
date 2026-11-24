package com.bidstay.hotel;

import com.bidstay.audit.AuditTrail;
import com.bidstay.audit.AuditTrail.Action;
import com.bidstay.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ListingMedia {
    private final JdbcTemplate jdbc;
    private final DocumentFiles files;
    private final HotelRepository hotels;
    private final RoomTypeRepository rooms;
    private final HotelDocumentFiles documents;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;

    private static final String PHOTO_SELECT = """
            select m.id, m.hotel_id, h.name, m.room_type_id, r.name, m.caption, m.status, m.review_reason, m.created_at
            from hotel_media m join hotels h on h.id = m.hotel_id left join room_types r on r.id = m.room_type_id
            """;
    private static final RowMapper<ReviewPhoto> PHOTO_MAPPER = (rs, row) -> new ReviewPhoto(rs.getLong(1),
            rs.getLong(2), rs.getString(3), rs.getObject(4, Long.class), rs.getString(5), rs.getString(6),
            rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant());

    public PartnerGallery gallery(long hotelId) {
        var roomChoices = rooms.findByHotelIdOrderById(hotelId).stream()
                .map(r -> new RoomChoice(r.getId(), r.getName())).toList();
        var photos = jdbc.query(PHOTO_SELECT + " where m.hotel_id = ? and m.status <> 'WITHDRAWN' order by m.id desc",
                PHOTO_MAPPER, hotelId);
        return new PartnerGallery(roomChoices, photos);
    }

    public PhotoPage queue(long before) {
        var rows = jdbc.query(PHOTO_SELECT + " where m.status = 'PENDING' and m.id < ? order by m.id desc limit 25",
                PHOTO_MAPPER, before);
        return rows.size() > 24 ? new PhotoPage(rows.subList(0, 24), rows.get(23).id()) : new PhotoPage(rows, null);
    }

    public void upload(long caller, long hotelId, Long roomTypeId, String caption, MultipartFile file) throws IOException {
        if (file.getSize() > ListingImage.MAX_BYTES) throw ApiException.invalid("Choose a photo under 5 MB");
        if (roomTypeId != null && !rooms.existsByIdAndHotelId(roomTypeId, hotelId)) throw ApiException.notFound("Room type");
        persist(caller, hotelId, roomTypeId, caption, file.getBytes(), null);
    }

    public void publishDocumentPhoto(long caller, long hotelId, long version) {
        assertApprovedPhotoDocument(hotelId, version);
        var document = documents.download(hotelId, DocType.PHOTOS, version);
        if (!List.of("image/jpeg", "image/png").contains(document.mediaType())) {
            throw ApiException.invalid("Upload the property photo as a JPEG or PNG under Listing photos");
        }
        persist(caller, hotelId, null, "Property photo", document.bytes(), version);
    }

    @Transactional
    public void review(long caller, long id, boolean approved, String reason) {
        String detail = reason == null ? null : reason.strip();
        if (!approved && (detail == null || detail.isBlank() || detail.length() > 180)) {
            throw ApiException.invalid("Enter a reason of up to 180 characters");
        }
        long hotelId = jdbc.query("""
                update hotel_media set status = ?, reviewed_by = ?, reviewed_at = now(), review_reason = ?
                where id = ? and status = 'PENDING' returning hotel_id
                """, (rs, row) -> rs.getLong(1), approved ? "APPROVED" : "REJECTED", caller, approved ? null : detail, id)
                .stream().findFirst().orElseThrow(() -> ApiException.conflict("This photo has already been reviewed or removed"));
        audit.record(caller, approved ? Action.LISTING_PHOTO_APPROVED : Action.LISTING_PHOTO_REJECTED, id, hotelId);
    }

    public void withdraw(long hotelId, long id) {
        if (jdbc.update("update hotel_media set status = 'WITHDRAWN' where id = ? and hotel_id = ? and status <> 'WITHDRAWN'",
                id, hotelId) != 1) throw ApiException.notFound("Photo");
    }

    public byte[] publicImage(long id, boolean thumbnail) {
        var photo = jdbc.query("""
                select m.object_key, m.storage from hotel_media m join hotels h on h.id = m.hotel_id
                where m.id = ? and m.status = 'APPROVED' and h.status = 'ACTIVE'
                """, (rs, row) -> new Stored(rs.getString(1), rs.getString(2)), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Photo"));
        return image(photo, thumbnail);
    }

    public byte[] privateImage(Long hotelId, long id) {
        var photo = jdbc.query("""
                select object_key, storage from hotel_media where id = ? and (cast(? as bigint) is null or hotel_id = ?) and status <> 'WITHDRAWN'
                """,
                (rs, row) -> new Stored(rs.getString(1), rs.getString(2)), id,
                hotelId, hotelId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Photo"));
        return image(photo, true);
    }

    private byte[] image(Stored row, boolean thumbnail) {
        return files.download(row.storage(), row.key() + (thumbnail ? "/thumb.jpg" : "/full.jpg"));
    }

    private void persist(long caller, long hotelId, Long roomTypeId, String caption, byte[] bytes, Long document) {
        String label = caption == null ? null : caption.strip();
        if (label != null && label.length() > 100) throw ApiException.invalid("Use a caption of up to 100 characters");
        var image = ListingImage.create(bytes);
        String key = "listing-media/" + UUID.randomUUID();
        String provider = files.upload(key + "/full.jpg", "image/jpeg", image.full());
        try {
            files.upload(key + "/thumb.jpg", "image/jpeg", image.thumbnail());
            transactions.executeWithoutResult(tx -> {
                hotels.lockById(hotelId).orElseThrow(() -> ApiException.notFound("Hotel"));
                if (document != null) assertApprovedPhotoDocument(hotelId, document);
                Integer count = jdbc.queryForObject(
                        "select count(*) from hotel_media where hotel_id = ? and status in ('PENDING','APPROVED')", Integer.class, hotelId);
                if (count != null && count >= 40) throw ApiException.conflict("Remove a photo before adding more");
                var created = jdbc.query("""
                        insert into hotel_media
                          (hotel_id,room_type_id,uploaded_by,caption,object_key,storage,status,reviewed_by,reviewed_at,source_document_id)
                        values (?,?,?,?,?,?,?,?,case when cast(? as bigint) is null then null else now() end,?)
                        on conflict (source_document_id) do nothing returning id
                        """, (rs, row) -> rs.getLong(1),
                        hotelId, roomTypeId, caller, label, key, provider, document == null ? "PENDING" : "APPROVED",
                        document == null ? null : caller, document, document);
                if (created.isEmpty()) throw ApiException.conflict("This verification photo is already in the gallery");
                if (document != null) audit.record(caller, Action.LISTING_PHOTO_PUBLISHED, created.getFirst(), hotelId);
            });
        } catch (RuntimeException failure) {
            files.remove(provider, key + "/full.jpg");
            files.remove(provider, key + "/thumb.jpg");
            throw failure;
        }
    }

    private void assertApprovedPhotoDocument(long hotelId, long version) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from hotel_documents where hotel_id = ? and doc_type = 'PHOTOS'
                  and version_id = ? and verified)
                """, Boolean.class, hotelId, version))) throw ApiException.conflict("Verify the current photos document before publishing");
    }

    public static String thumbnail(Long id) {
        return id == null ? null : "/api/v1/media/" + id + "/thumb";
    }

    private record Stored(String key, String storage) {}
    public record ReviewPhoto(long id, long hotelId, String hotelName, Long roomTypeId, String roomName, String caption,
                              String status, String reviewReason, Instant createdAt) {}
    public record RoomChoice(long id, String name) {}
    public record PartnerGallery(List<RoomChoice> rooms, List<ReviewPhoto> photos) {}
    public record PhotoPage(List<ReviewPhoto> photos, Long nextCursor) {}
}
