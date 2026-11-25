ALTER TABLE bookings
    ALTER COLUMN bid_request_id DROP NOT NULL,
    ALTER COLUMN bid_offer_id DROP NOT NULL,
    ADD COLUMN direct_request_id UUID,
    ADD COLUMN guest_count INTEGER CHECK (guest_count BETWEEN 1 AND 40),
    ADD CONSTRAINT booking_source CHECK (
        (bid_request_id IS NOT NULL AND bid_offer_id IS NOT NULL AND direct_request_id IS NULL)
        OR (bid_request_id IS NULL AND bid_offer_id IS NULL AND direct_request_id IS NOT NULL AND guest_count IS NOT NULL)
    );

CREATE UNIQUE INDEX idx_bookings_direct_request ON bookings (guest_id, direct_request_id)
    WHERE direct_request_id IS NOT NULL;
CREATE INDEX idx_bookings_guest_direct ON bookings (guest_id, id DESC)
    WHERE direct_request_id IS NOT NULL;
