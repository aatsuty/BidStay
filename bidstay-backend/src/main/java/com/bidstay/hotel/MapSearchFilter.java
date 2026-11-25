package com.bidstay.hotel;

import com.bidstay.common.ApiException;

public record MapSearchFilter(Double latitude, Double longitude, Double south, Double west, Double north, Double east) {
    static final MapSearchFilter NONE = new MapSearchFilter(null, null, null, null, null, null);

    public void validate() {
        validateCoordinates(latitude, longitude);
        if (south == null && west == null && north == null && east == null) return;
        if (!coordinate(south, 90) || !coordinate(north, 90) || south >= north
                || !coordinate(west, 180) || !coordinate(east, 180) || west.equals(east)) {
            throw ApiException.invalid("Choose valid map bounds");
        }
    }

    public static void validateCoordinates(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) return;
        if (!coordinate(latitude, 90) || !coordinate(longitude, 180)) {
            throw ApiException.invalid("Choose a valid location");
        }
    }

    private static boolean coordinate(Double value, int max) {
        return value != null && Double.isFinite(value) && Math.abs(value) <= max;
    }
}
