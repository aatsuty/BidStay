package com.bidstay.hotel;

import java.math.BigDecimal;

public record HotelSummary(Long id, String name, String area, Integer starRating,
                           RoomCategory category, BigDecimal listedPrice) {
}
