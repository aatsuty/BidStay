-- BidStay core schema. Money is NUMERIC(12,2) in INR. All timestamps are UTC.

CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    phone       VARCHAR(15)  NOT NULL UNIQUE,
    name        VARCHAR(100),
    role        VARCHAR(20)  NOT NULL CHECK (role IN ('GUEST','HOTEL_STAFF','ADMIN')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE hotels (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(150) NOT NULL UNIQUE,
    city          VARCHAR(80)  NOT NULL,
    area          VARCHAR(80)  NOT NULL,
    address       VARCHAR(300) NOT NULL,           -- hidden from guests until payment
    latitude      DOUBLE PRECISION,
    longitude     DOUBLE PRECISION,
    star_rating   INTEGER     CHECK (star_rating BETWEEN 0 AND 5),
    property_type VARCHAR(30)  NOT NULL DEFAULT 'HOTEL',
    gst_number    VARCHAR(20),
    status        VARCHAR(30)  NOT NULL DEFAULT 'PENDING_VERIFICATION'
                  CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','SUSPENDED')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_hotels_city_status ON hotels (lower(city), status);

CREATE TABLE hotel_staff (
    user_id   BIGINT NOT NULL REFERENCES users(id),
    hotel_id  BIGINT NOT NULL REFERENCES hotels(id),
    PRIMARY KEY (user_id, hotel_id)
);

CREATE TABLE room_types (
    id           BIGSERIAL PRIMARY KEY,
    hotel_id     BIGINT        NOT NULL REFERENCES hotels(id),
    category     VARCHAR(20)   NOT NULL CHECK (category IN ('STANDARD','DELUXE','SUITE')),
    name         VARCHAR(80)   NOT NULL,
    max_guests   INTEGER      NOT NULL DEFAULT 2,
    listed_price NUMERIC(12,2) NOT NULL CHECK (listed_price > 0),
    min_price    NUMERIC(12,2) NOT NULL CHECK (min_price > 0),   -- SECRET: never exposed to guests
    total_rooms  INT           NOT NULL CHECK (total_rooms > 0),
    CHECK (min_price <= listed_price),
    UNIQUE (hotel_id, category)
);

-- One row per room type per night. The CHECK is the last line of defence against double booking.
CREATE TABLE room_inventory (
    id           BIGSERIAL PRIMARY KEY,
    room_type_id BIGINT NOT NULL REFERENCES room_types(id),
    stay_date    DATE   NOT NULL,
    total_rooms  INT    NOT NULL CHECK (total_rooms >= 0),
    held_rooms   INT    NOT NULL DEFAULT 0 CHECK (held_rooms >= 0),   -- guest is paying
    sold_rooms   INT    NOT NULL DEFAULT 0 CHECK (sold_rooms >= 0),   -- paid and confirmed
    UNIQUE (room_type_id, stay_date),
    CHECK (held_rooms + sold_rooms <= total_rooms)
);

CREATE TABLE bid_requests (
    id               BIGSERIAL PRIMARY KEY,
    guest_id         BIGINT        NOT NULL REFERENCES users(id),
    city             VARCHAR(80)   NOT NULL,
    check_in         DATE          NOT NULL,
    check_out        DATE          NOT NULL,
    rooms            INTEGER      NOT NULL CHECK (rooms > 0),
    guests           INTEGER      NOT NULL CHECK (guests > 0),
    category         VARCHAR(20)   NOT NULL,
    price_per_night  NUMERIC(12,2) NOT NULL CHECK (price_per_night > 0),
    round            INTEGER      NOT NULL DEFAULT 1,
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('OPEN','HELD','BOOKED','CANCELLED','EXPIRED')),
    expires_at       TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (check_out > check_in)
);
CREATE INDEX idx_bids_guest ON bid_requests (guest_id, created_at DESC);
CREATE INDEX idx_bids_open_expiry ON bid_requests (expires_at) WHERE status = 'OPEN';

CREATE TABLE bid_offers (
    id             BIGSERIAL PRIMARY KEY,
    bid_request_id BIGINT        NOT NULL REFERENCES bid_requests(id),
    hotel_id       BIGINT        NOT NULL REFERENCES hotels(id),
    room_type_id   BIGINT        NOT NULL REFERENCES room_types(id),
    status         VARCHAR(20)   NOT NULL
                   CHECK (status IN ('PENDING','ACCEPTED','COUNTERED','DECLINED','EXPIRED','UNAVAILABLE','CANCELLED','SELECTED')),
    offered_price  NUMERIC(12,2),          -- per night; bid price if ACCEPTED, counter if COUNTERED
    round          INTEGER      NOT NULL DEFAULT 1,
    auto_accepted  BOOLEAN       NOT NULL DEFAULT FALSE,
    expires_at     TIMESTAMPTZ,
    responded_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0,   -- optimistic lock: hotel reply vs expiry job
    UNIQUE (bid_request_id, hotel_id)
);
CREATE INDEX idx_offers_hotel_status ON bid_offers (hotel_id, status);
CREATE INDEX idx_offers_pending_expiry ON bid_offers (expires_at) WHERE status = 'PENDING';

CREATE TABLE bookings (
    id                BIGSERIAL PRIMARY KEY,
    bid_request_id    BIGINT        NOT NULL REFERENCES bid_requests(id),
    bid_offer_id      BIGINT        NOT NULL UNIQUE REFERENCES bid_offers(id),
    guest_id          BIGINT        NOT NULL REFERENCES users(id),
    hotel_id          BIGINT        NOT NULL REFERENCES hotels(id),
    room_type_id      BIGINT        NOT NULL REFERENCES room_types(id),
    check_in          DATE          NOT NULL,
    check_out         DATE          NOT NULL,
    rooms             INTEGER      NOT NULL,
    price_per_night   NUMERIC(12,2) NOT NULL,
    total_amount      NUMERIC(12,2) NOT NULL,
    commission_pct    NUMERIC(5,2)  NOT NULL,
    commission_amount NUMERIC(12,2) NOT NULL,
    status            VARCHAR(20)   NOT NULL
                      CHECK (status IN ('PENDING_PAYMENT','CONFIRMED','EXPIRED','CANCELLED')),
    hold_expires_at   TIMESTAMPTZ   NOT NULL,
    payment_ref       VARCHAR(100),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_bookings_hold ON bookings (hold_expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_bookings_guest ON bookings (guest_id, created_at DESC);
