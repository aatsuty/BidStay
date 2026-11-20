package com.bidstay.hotel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HotelDocumentRepository extends JpaRepository<HotelDocument, Long> {
    List<HotelDocument> findByHotelId(Long hotelId);

    Optional<HotelDocument> findByHotelIdAndDocType(Long hotelId, DocType type);
}
