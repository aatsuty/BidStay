CREATE TABLE phone_verifications (
    phone             VARCHAR(10) PRIMARY KEY,
    resend_at         TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    verification_sid  VARCHAR(40)
);

CREATE INDEX idx_phone_verifications_expiry ON phone_verifications (expires_at);

CREATE TABLE auth_sessions (
    token_hash  VARCHAR(64) PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_auth_sessions_expiry ON auth_sessions (expires_at);
