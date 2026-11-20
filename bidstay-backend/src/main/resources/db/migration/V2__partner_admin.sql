-- Hotelier portal + admin portal + WhatsApp-style chat.

ALTER TABLE hotels DROP CONSTRAINT hotels_status_check;
ALTER TABLE hotels ADD CONSTRAINT hotels_status_check
    CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','REJECTED','SUSPENDED'));

-- How this hotel answers bids on its own (matches the demo's Auto / Manual modes)
ALTER TABLE hotels
    ADD COLUMN auto_mode        BOOLEAN      NOT NULL DEFAULT TRUE,   -- accept bids >= minimum price automatically
    ADD COLUMN auto_counter     BOOLEAN      NOT NULL DEFAULT FALSE,  -- counter bids slightly below the minimum
    ADD COLUMN auto_counter_pct INTEGER      NOT NULL DEFAULT 10 CHECK (auto_counter_pct BETWEEN 1 AND 30),
    ADD COLUMN auto_decline     BOOLEAN      NOT NULL DEFAULT FALSE,  -- decline everything below that range
    ADD COLUMN contact_phone    VARCHAR(15),
    ADD COLUMN rejection_reason VARCHAR(300);

-- The four documents the admin verifies before a hotel goes live
CREATE TABLE hotel_documents (
    id           BIGSERIAL PRIMARY KEY,
    hotel_id     BIGINT       NOT NULL REFERENCES hotels(id),
    doc_type     VARCHAR(20)  NOT NULL CHECK (doc_type IN ('GST','LICENCE','OWNER_ID','PHOTOS')),
    reference    VARCHAR(300),                       -- document number or file link (real upload comes later)
    submitted_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    verified     BOOLEAN      NOT NULL DEFAULT FALSE,
    verified_at  TIMESTAMPTZ,
    UNIQUE (hotel_id, doc_type)
);

-- Everything the platform "says" to a hotel on WhatsApp, and what the hotel replies.
-- Today the portal shows this as a WhatsApp-style chat. A real WhatsApp Business gateway will
-- send the same BOT messages out and write inbound replies as HOTEL messages.
CREATE TABLE partner_messages (
    id           BIGSERIAL PRIMARY KEY,
    hotel_id     BIGINT      NOT NULL REFERENCES hotels(id),
    sender       VARCHAR(10) NOT NULL CHECK (sender IN ('BOT','HOTEL')),
    kind         VARCHAR(10) NOT NULL CHECK (kind IN ('REQUEST','SYSTEM','REPLY')),
    body         TEXT        NOT NULL,
    bid_offer_id BIGINT      REFERENCES bid_offers(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_partner_messages_hotel ON partner_messages (hotel_id, id);
