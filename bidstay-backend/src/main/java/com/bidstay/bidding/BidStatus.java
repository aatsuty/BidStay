package com.bidstay.bidding;

public enum BidStatus {
    OPEN,       // hotels are responding, guest may accept or raise
    HELD,       // guest picked an offer, inventory held, waiting for payment
    BOOKED,     // paid
    CANCELLED,
    EXPIRED
}
