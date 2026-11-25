CREATE TABLE hotel_document_versions (
    id            BIGSERIAL PRIMARY KEY,
    hotel_id      BIGINT NOT NULL REFERENCES hotels(id),
    doc_type      VARCHAR(20) NOT NULL CHECK (doc_type IN ('GST','LICENCE','OWNER_ID','PHOTOS')),
    uploaded_by   BIGINT NOT NULL REFERENCES users(id),
    file_name     VARCHAR(180) NOT NULL,
    media_type    VARCHAR(32) NOT NULL,
    object_key    VARCHAR(100) NOT NULL UNIQUE,
    storage       VARCHAR(8) NOT NULL CHECK (storage IN ('S3','LOCAL')),
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE hotel_documents ADD COLUMN version_id BIGINT REFERENCES hotel_document_versions(id);

CREATE TABLE hotel_document_reviews (
    id            BIGSERIAL PRIMARY KEY,
    version_id    BIGINT NOT NULL REFERENCES hotel_document_versions(id),
    reviewed_by   BIGINT NOT NULL REFERENCES users(id),
    verified      BOOLEAN NOT NULL,
    reviewed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_versions_hotel ON hotel_document_versions (hotel_id, doc_type, id DESC);
CREATE INDEX idx_document_reviews_version ON hotel_document_reviews (version_id, id DESC);
