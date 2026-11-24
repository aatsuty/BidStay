CREATE TABLE saved_stay_searches (
    id               BIGSERIAL PRIMARY KEY,
    guest_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    city             VARCHAR(80) NOT NULL,
    area             VARCHAR(100) NOT NULL DEFAULT '',
    check_in         DATE NOT NULL,
    check_out        DATE NOT NULL CHECK (check_out > check_in),
    category         VARCHAR(16) NOT NULL CHECK (category IN ('STANDARD','DELUXE','SUITE')),
    rooms            INTEGER NOT NULL CHECK (rooms BETWEEN 1 AND 10),
    guests           INTEGER NOT NULL CHECK (guests BETWEEN 1 AND 40),
    budget           NUMERIC(12,2) NOT NULL CHECK (budget > 0),
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    next_check_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (guest_id, city, area, check_in, check_out, category, rooms, guests, budget)
);
CREATE INDEX idx_saved_searches_due ON saved_stay_searches (next_check_at, id) WHERE enabled;
CREATE INDEX idx_saved_searches_guest ON saved_stay_searches (guest_id, id DESC);

CREATE TABLE guest_alerts (
    id            BIGSERIAL PRIMARY KEY,
    guest_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_key     VARCHAR(120) NOT NULL,
    kind          VARCHAR(24) NOT NULL CHECK (kind IN ('OFFER','BOOKING','CANCELLATION','PRICE_MATCH')),
    title         VARCHAR(120) NOT NULL,
    body          VARCHAR(350) NOT NULL,
    bid_id        BIGINT REFERENCES bid_requests(id) ON DELETE CASCADE,
    booking_id    BIGINT REFERENCES bookings(id) ON DELETE CASCADE,
    search_id     BIGINT REFERENCES saved_stay_searches(id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ,
    UNIQUE (guest_id, event_key)
);
CREATE INDEX idx_guest_alerts_recent ON guest_alerts (guest_id, id DESC);
CREATE INDEX idx_guest_alerts_unread ON guest_alerts (guest_id) WHERE read_at IS NULL;
CREATE INDEX idx_guest_alerts_saved_search ON guest_alerts (search_id) WHERE search_id IS NOT NULL;
