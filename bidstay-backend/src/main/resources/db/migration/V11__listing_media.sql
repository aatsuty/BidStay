ALTER TABLE room_types ADD CONSTRAINT uq_room_type_hotel UNIQUE (id, hotel_id);

CREATE TABLE hotel_media (
    id                  BIGSERIAL PRIMARY KEY,
    hotel_id            BIGINT NOT NULL REFERENCES hotels(id),
    room_type_id        BIGINT,
    uploaded_by         BIGINT NOT NULL REFERENCES users(id),
    caption             VARCHAR(100),
    object_key          VARCHAR(100) NOT NULL UNIQUE,
    storage             VARCHAR(8) NOT NULL CHECK (storage IN ('S3', 'LOCAL')),
    status              VARCHAR(12) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at         TIMESTAMPTZ,
    reviewed_by         BIGINT REFERENCES users(id),
    review_reason       VARCHAR(180),
    source_document_id  BIGINT UNIQUE REFERENCES hotel_document_versions(id),
    FOREIGN KEY (room_type_id, hotel_id) REFERENCES room_types(id, hotel_id)
);

CREATE INDEX idx_hotel_media_public ON hotel_media (hotel_id, room_type_id, id) WHERE status = 'APPROVED';
CREATE INDEX idx_hotel_media_queue ON hotel_media (id DESC) WHERE status = 'PENDING';
CREATE INDEX idx_hotel_media_hotel ON hotel_media (hotel_id, id DESC);
