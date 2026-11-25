CREATE TABLE payment_links (
    booking_id    BIGINT PRIMARY KEY REFERENCES bookings(id),
    provider_id   VARCHAR(64) NOT NULL UNIQUE,
    checkout_url  VARCHAR(512) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payment_captures (
    provider_payment_id  VARCHAR(64) PRIMARY KEY,
    booking_id           BIGINT NOT NULL REFERENCES bookings(id),
    amount_paise         BIGINT NOT NULL CHECK (amount_paise > 0),
    status               VARCHAR(30) NOT NULL CHECK (status IN (
        'APPLIED', 'REFUND_PENDING', 'REFUND_SUBMITTED', 'REFUND_PROCESSED', 'REFUND_REVIEW')),
    refund_id            VARCHAR(64) UNIQUE,
    attempts             INTEGER NOT NULL DEFAULT 0,
    retry_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    captured_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_captures_booking ON payment_captures (booking_id);
CREATE INDEX idx_captures_refund_queue ON payment_captures (retry_at)
    WHERE status = 'REFUND_PENDING';
