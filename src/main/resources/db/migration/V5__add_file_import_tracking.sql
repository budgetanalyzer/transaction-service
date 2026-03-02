-- File import tracking for duplicate prevention
-- Records each successfully imported CSV file by content hash per user

CREATE TABLE file_import (
    id                  BIGSERIAL PRIMARY KEY,
    content_hash        VARCHAR(64) NOT NULL,
    original_filename   VARCHAR(255) NOT NULL,
    format              VARCHAR(50) NOT NULL,
    account_id          VARCHAR(255),
    file_size_bytes     BIGINT NOT NULL,
    transaction_count   INTEGER NOT NULL,
    imported_by         VARCHAR(50) NOT NULL,
    imported_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- Unique constraint per user (same user can't import same file twice)
CREATE UNIQUE INDEX idx_file_import_hash_user ON file_import(content_hash, imported_by);

-- Index for querying by import date
CREATE INDEX idx_file_import_imported_at ON file_import(imported_at);
