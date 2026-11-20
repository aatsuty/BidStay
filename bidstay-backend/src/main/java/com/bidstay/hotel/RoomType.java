package com.bidstay.hotel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "room_types")
@Getter @Setter @NoArgsConstructor
public class RoomType {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long hotelId;
    @Enumerated(EnumType.STRING)
    private RoomCategory category;
    private String name;
    private Integer maxGuests;
    private BigDecimal listedPrice;
    /** SECRET. Only the bidding engine reads this. Never put it in a guest-facing DTO. */
    private BigDecimal minPrice;
    private Integer totalRooms;
}
