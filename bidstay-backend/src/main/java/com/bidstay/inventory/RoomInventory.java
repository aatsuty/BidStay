package com.bidstay.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "room_inventory")
@Getter @Setter @NoArgsConstructor
public class RoomInventory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long roomTypeId;
    private LocalDate stayDate;
    private int totalRooms;
    private int heldRooms;
    private int soldRooms;

    public int available() {
        return totalRooms - heldRooms - soldRooms;
    }
}
