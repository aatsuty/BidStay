ALTER TABLE users
    ALTER COLUMN phone DROP NOT NULL,
    ADD COLUMN guest_sign_in_email VARCHAR(254) UNIQUE,
    ADD CONSTRAINT users_sign_in_identity CHECK (
        (phone IS NOT NULL OR guest_sign_in_email IS NOT NULL)
        AND (guest_sign_in_email IS NULL OR role = 'GUEST')
    );

ALTER TABLE auth_sessions ADD COLUMN guest_sms_verified_at TIMESTAMPTZ;

CREATE TABLE guest_email_codes (
    email VARCHAR(254) PRIMARY KEY,
    challenge_id UUID NOT NULL,
    link_guest_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    code_digest BYTEA,
    attempts INTEGER NOT NULL DEFAULT 0,
    resend_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX guest_email_codes_expiry ON guest_email_codes(expires_at);
