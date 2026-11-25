CREATE TABLE booking_reviews (
    booking_id BIGINT PRIMARY KEY REFERENCES bookings(id),
    hotel_id BIGINT NOT NULL REFERENCES hotels(id),
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment VARCHAR(500),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'HIDDEN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_booking_reviews_public ON booking_reviews (hotel_id, booking_id DESC) WHERE status = 'PUBLISHED';
CREATE INDEX idx_booking_reviews_moderation ON booking_reviews (status, booking_id DESC);
