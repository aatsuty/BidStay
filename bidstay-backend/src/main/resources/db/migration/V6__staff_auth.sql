ALTER TABLE auth_sessions ADD COLUMN scope_role VARCHAR(20) NOT NULL DEFAULT 'GUEST'
    CHECK (scope_role IN ('GUEST', 'HOTEL_STAFF', 'ADMIN'));

CREATE TABLE staff_invites (
    token_hash   VARCHAR(64) PRIMARY KEY,
    hotel_id     BIGINT NOT NULL REFERENCES hotels(id),
    phone        VARCHAR(10) NOT NULL,
    created_by   BIGINT NOT NULL REFERENCES users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    accepted_at  TIMESTAMPTZ,
    accepted_by  BIGINT REFERENCES users(id)
);

CREATE INDEX idx_staff_invites_hotel ON staff_invites (hotel_id, created_at DESC);

CREATE TABLE admin_auth_state (
    user_id       BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    attempts      INTEGER NOT NULL DEFAULT 0,
    window_end    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_totp     BIGINT NOT NULL DEFAULT -1
);
