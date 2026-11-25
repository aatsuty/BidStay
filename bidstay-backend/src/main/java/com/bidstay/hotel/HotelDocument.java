package com.bidstay.hotel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "hotel_documents")
@Getter @Setter @NoArgsConstructor
public class HotelDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long hotelId;
    @Enumerated(EnumType.STRING)
    private DocType docType;
    private String reference;
    private Long versionId;
    private Instant submittedAt;
    private boolean verified;
    private Instant verifiedAt;
}
