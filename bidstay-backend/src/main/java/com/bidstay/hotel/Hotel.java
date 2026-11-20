package com.bidstay.hotel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "hotels")
@Getter @Setter @NoArgsConstructor
public class Hotel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String city;
    private String area;
    private String address;
    private Double latitude;
    private Double longitude;
    private Integer starRating;
    @Enumerated(EnumType.STRING)
    private HotelStatus status;
    private String gstNumber;
    private String contactPhone;
    private String rejectionReason;
    /** Accept bids at or above the minimum price without asking the owner. */
    private boolean autoMode = true;
    /** Counter bids that are within autoCounterPct% below the minimum. */
    private boolean autoCounter;
    private int autoCounterPct = 10;
    /** Decline everything below the auto-counter range instead of waiting for the owner. */
    private boolean autoDecline;
    @Column(insertable = false, updatable = false)   // DB default now()
    private Instant createdAt;
}
