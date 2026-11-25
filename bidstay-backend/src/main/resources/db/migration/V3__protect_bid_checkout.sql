ALTER TABLE bid_requests
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX idx_bookings_one_active_per_bid
    ON bookings (bid_request_id)
    WHERE status IN ('PENDING_PAYMENT', 'CONFIRMED');
