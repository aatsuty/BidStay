package com.bidstay.partner;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartnerMessageRepository extends JpaRepository<PartnerMessage, Long> {
    List<PartnerMessage> findTop100ByHotelIdOrderByIdDesc(Long hotelId);

    List<PartnerMessage> findByHotelIdAndIdGreaterThanOrderByIdAsc(Long hotelId, Long afterId);
}
