ALTER TABLE users ADD COLUMN guest_phone_verified BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users u SET guest_phone_verified = TRUE
WHERE u.role = 'GUEST' AND EXISTS (
    SELECT 1 FROM auth_sessions s WHERE s.user_id = u.id AND s.scope_role = 'GUEST'
);

CREATE TABLE guest_ticket_contacts (
    guest_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    email VARCHAR(254),
    sms_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    pending_email VARCHAR(254),
    code_hash VARCHAR(60),
    code_expires_at TIMESTAMPTZ,
    resend_at TIMESTAMPTZ,
    code_attempts SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE booking_ticket_outbox (
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    channel VARCHAR(5) NOT NULL CHECK (channel IN ('SMS', 'EMAIL')),
    destination VARCHAR(254) NOT NULL,
    state VARCHAR(10) NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED', 'SUBMITTED', 'FAILED', 'SKIPPED')),
    attempts SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    send_before TIMESTAMPTZ NOT NULL,
    provider_id VARCHAR(255),
    PRIMARY KEY (booking_id, channel)
);

CREATE INDEX idx_ticket_outbox_due ON booking_ticket_outbox (next_attempt_at, booking_id, channel) WHERE state = 'QUEUED';

ALTER TABLE whatsapp_outbox ADD COLUMN ticket_booking_id BIGINT REFERENCES bookings(id) ON DELETE CASCADE;

CREATE TABLE whatsapp_opt_out_events (
    provider_message_id VARCHAR(255) PRIMARY KEY
);
