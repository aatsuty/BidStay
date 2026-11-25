ALTER TABLE hotel_staff ADD COLUMN can_manage_staff BOOLEAN NOT NULL DEFAULT TRUE;
-- A member provisioned by an earlier invitation retains property access as a staff member.
UPDATE hotel_staff s SET can_manage_staff = FALSE
WHERE EXISTS (
    SELECT 1 FROM staff_invites i WHERE i.hotel_id = s.hotel_id AND i.accepted_by = s.user_id
);
ALTER TABLE hotel_staff ALTER COLUMN can_manage_staff SET DEFAULT FALSE;
CREATE INDEX idx_hotel_staff_roster ON hotel_staff (hotel_id, user_id);

ALTER TABLE staff_invites
    ADD COLUMN id BIGSERIAL UNIQUE,
    ADD COLUMN can_manage_staff BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN revoked_by BIGINT REFERENCES users(id);

CREATE INDEX idx_staff_invites_pending ON staff_invites (hotel_id, phone)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE TABLE auth_rate_limits (
    scope       VARCHAR(24) NOT NULL,
    client      CIDR NOT NULL,
    window_end  TIMESTAMPTZ NOT NULL,
    hits        INTEGER NOT NULL CHECK (hits > 0),
    PRIMARY KEY (scope, client)
);
CREATE INDEX idx_auth_rate_limits_expiry ON auth_rate_limits (window_end);

CREATE TABLE audit_events (
    id           BIGSERIAL PRIMARY KEY,
    actor_id     BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action       VARCHAR(48) NOT NULL,
    subject_type VARCHAR(20) NOT NULL,
    subject_id   BIGINT NOT NULL,
    hotel_id     BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_hotel ON audit_events (hotel_id, id DESC) WHERE hotel_id IS NOT NULL;
CREATE INDEX idx_audit_action ON audit_events (action, id DESC);
