package com.bidstay.bidding;

import java.util.EnumSet;
import java.util.Set;

public enum OfferStatus {
    PENDING,      // waiting for the hotel
    ACCEPTED,     // hotel (or auto-accept) agreed to the guest's bid price
    COUNTERED,    // hotel proposed a higher price
    DECLINED,     // hotel said no
    EXPIRED,      // hotel did not reply in time
    UNAVAILABLE,  // no rooms for these dates (decided by the system)
    CANCELLED,    // another offer was chosen, or the bid closed
    SELECTED;     // guest chose this offer; booking exists

    /** Offers the guest can still pick from. */
    public static final Set<OfferStatus> BOOKABLE = EnumSet.of(ACCEPTED, COUNTERED);
    /** Offers that a price raise re-opens. UNAVAILABLE stays closed (nothing to negotiate). */
    public static final Set<OfferStatus> REOPENABLE = EnumSet.of(PENDING, COUNTERED, DECLINED, EXPIRED);
    /** Offers still "live" when the guest picks one or the bid closes. */
    public static final Set<OfferStatus> LIVE = EnumSet.of(PENDING, ACCEPTED, COUNTERED);
}
