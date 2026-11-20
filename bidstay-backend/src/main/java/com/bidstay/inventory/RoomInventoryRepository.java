package com.bidstay.inventory;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RoomInventoryRepository extends JpaRepository<RoomInventory, Long> {

    @Query("""
            select i from RoomInventory i
            where i.roomTypeId = :rt and i.stayDate >= :from and i.stayDate < :to
            order by i.stayDate
            """)
    List<RoomInventory> findRange(@Param("rt") Long roomTypeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** SELECT ... FOR UPDATE, always ordered by date so concurrent holds cannot deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from RoomInventory i
            where i.roomTypeId = :rt and i.stayDate >= :from and i.stayDate < :to
            order by i.stayDate
            """)
    List<RoomInventory> lockRange(@Param("rt") Long roomTypeId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
