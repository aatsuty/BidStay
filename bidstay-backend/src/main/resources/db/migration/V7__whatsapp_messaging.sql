CREATE TABLE whatsapp_consents (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id),
    hotel_id    BIGINT REFERENCES hotels(id),
    enabled     BOOLEAN NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (user_id, hotel_id)
);

ALTER TABLE partner_messages ADD COLUMN channel VARCHAR(15) NOT NULL DEFAULT 'PORTAL'
    CHECK (channel IN ('PORTAL', 'WHATSAPP'));
ALTER TABLE partner_messages ADD COLUMN provider_message_id VARCHAR(255) UNIQUE;

CREATE TABLE whatsapp_outbox (
    id                  BIGSERIAL PRIMARY KEY,
    consent_id          BIGINT NOT NULL REFERENCES whatsapp_consents(id),
    event_key           TEXT NOT NULL,
    template_name       VARCHAR(100),
    content             TEXT NOT NULL,
    partner_message_id  BIGINT REFERENCES partner_messages(id),
    offer_id            BIGINT REFERENCES bid_offers(id),
    round               INTEGER,
    send_before         TIMESTAMPTZ NOT NULL,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts            INTEGER NOT NULL DEFAULT 0,
    state               VARCHAR(15) NOT NULL DEFAULT 'QUEUED'
        CHECK (state IN ('QUEUED','SUBMITTED','FAILED','SKIPPED')),
    provider_message_id VARCHAR(255) UNIQUE,
    UNIQUE (consent_id, event_key)
);

CREATE INDEX idx_whatsapp_outbox_queue ON whatsapp_outbox (next_attempt_at) WHERE state = 'QUEUED';
CREATE INDEX idx_whatsapp_outbox_partner ON whatsapp_outbox (partner_message_id) WHERE partner_message_id IS NOT NULL;
CREATE INDEX idx_whatsapp_outbox_reply ON whatsapp_outbox (offer_id, round, consent_id) WHERE offer_id IS NOT NULL;

CREATE TABLE whatsapp_delivery (
    provider_message_id VARCHAR(255) PRIMARY KEY,
    status              VARCHAR(12) NOT NULL CHECK (status IN ('sent','delivered','read','failed')),
    priority            INTEGER NOT NULL,
    observed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE whatsapp_inbound (
    provider_message_id VARCHAR(255) PRIMARY KEY,
    consent_id          BIGINT NOT NULL REFERENCES whatsapp_consents(id),
    hotel_id            BIGINT NOT NULL REFERENCES hotels(id),
    offer_id            BIGINT NOT NULL REFERENCES bid_offers(id),
    round               INTEGER NOT NULL,
    body                VARCHAR(500) NOT NULL,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts            INTEGER NOT NULL DEFAULT 0,
    processed_at        TIMESTAMPTZ
);

CREATE INDEX idx_whatsapp_inbound_queue ON whatsapp_inbound (next_attempt_at) WHERE processed_at IS NULL;
