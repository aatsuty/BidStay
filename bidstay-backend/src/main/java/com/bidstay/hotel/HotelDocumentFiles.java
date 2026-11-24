package com.bidstay.hotel;

import com.bidstay.common.ApiException;
import com.bidstay.partner.PartnerChatService;
import com.bidstay.partner.PartnerMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HotelDocumentFiles {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final DocumentFiles files;
    private final HotelRepository hotels;
    private final HotelDocumentRepository documents;
    private final PartnerChatService chat;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public void upload(Long caller, Long hotelId, DocType type, MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw ApiException.invalid("Choose a file under 5 MB");
        byte[] bytes = file.getBytes();
        String mediaType = typeOf(bytes);
        String name = safeName(file.getOriginalFilename());
        String key = "hotel-documents/" + UUID.randomUUID();
        String provider = files.upload(key, mediaType, bytes);
        try {
            transactions.executeWithoutResult(transaction -> {
                Hotel hotel = hotels.lockById(hotelId).orElseThrow(() -> ApiException.notFound("Hotel"));
                Long version = jdbc.queryForObject("""
                        insert into hotel_document_versions
                        (hotel_id, doc_type, uploaded_by, file_name, media_type, object_key, storage)
                        values (?, ?, ?, ?, ?, ?, ?) returning id
                        """, Long.class, hotelId, type.name(), caller, name, mediaType, key, provider);
                var current = documents.findByHotelIdAndDocType(hotelId, type).orElseGet(HotelDocument::new);
                current.setHotelId(hotelId);
                current.setDocType(type);
                current.setReference(name);
                current.setVersionId(version);
                current.setSubmittedAt(clock.instant());
                current.setVerified(false);
                current.setVerifiedAt(null);
                documents.save(current);
                if (hotel.getStatus() == HotelStatus.ACTIVE || hotel.getStatus() == HotelStatus.REJECTED) {
                    hotel.setStatus(HotelStatus.PENDING_VERIFICATION);
                    hotel.setRejectionReason(null);
                }
                chat.bot(hotelId, PartnerMessage.Kind.SYSTEM, "Document uploaded for review.", null);
            });
        } catch (RuntimeException failure) {
            files.remove(provider, key);
            throw failure;
        }
    }

    public Download download(long hotelId, DocType type, long version) {
        var item = jdbc.query("""
                select object_key, storage, file_name, media_type from hotel_document_versions
                where id = ? and hotel_id = ? and doc_type = ?
                """, (rs, row) -> new Location(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                version, hotelId, type.name()).stream().findFirst().orElseThrow(() -> ApiException.notFound("Document"));
        return new Download(files.download(item.provider(), item.key()), item.name(), item.mediaType());
    }

    private static String typeOf(byte[] bytes) {
        if (bytes.length >= 5 && "%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII))) return "application/pdf";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) return "image/jpeg";
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && "PNG\r\n\u001a\n".equals(new String(bytes, 1, 7, StandardCharsets.US_ASCII))) return "image/png";
        throw ApiException.invalid("Only PDF, JPEG and PNG documents are accepted");
    }

    private static String safeName(String value) {
        String name = value == null ? "" : value.replaceAll("[^a-zA-Z0-9 ._-]", "_").strip();
        if (name.isBlank()) return "document";
        return name.substring(0, Math.min(180, name.length()));
    }

    public record Download(byte[] bytes, String name, String mediaType) {}
    private record Location(String key, String provider, String name, String mediaType) {}
}
