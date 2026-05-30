-- Add user/system visibility to statement formats.
ALTER TABLE statement_format ADD COLUMN scope VARCHAR(10);
ALTER TABLE statement_format ADD COLUMN owner_id VARCHAR(50);

UPDATE statement_format
SET scope = 'SYSTEM',
    owner_id = NULL
WHERE scope IS NULL;

ALTER TABLE statement_format ALTER COLUMN scope SET NOT NULL;
ALTER TABLE statement_format
    ADD CONSTRAINT chk_statement_format_scope CHECK (scope IN ('SYSTEM', 'USER'));

CREATE INDEX idx_statement_format_scope_owner ON statement_format(scope, owner_id);

-- Hidden parser revisions own deterministic parser configuration and static handler routing.
CREATE TABLE parser_revision (
    id BIGSERIAL PRIMARY KEY,
    statement_format_id BIGINT NOT NULL REFERENCES statement_format(id),
    revision_number INTEGER NOT NULL,
    parser_type VARCHAR(30) NOT NULL,
    handler_key VARCHAR(100),
    config_schema_version INTEGER NOT NULL,
    parser_config TEXT,
    priority INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    promoted_from_parser_revision_id BIGINT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_parser_revision_type CHECK (
        parser_type IN ('STATIC_HANDLER', 'CSV_COLUMN_CONFIG', 'PDF_TEXT_TABLE_CONFIG')
    ),
    CONSTRAINT uq_parser_revision_number UNIQUE (statement_format_id, revision_number),
    CONSTRAINT fk_parser_revision_promoted_from
        FOREIGN KEY (promoted_from_parser_revision_id) REFERENCES parser_revision(id)
);

CREATE INDEX idx_parser_revision_format_enabled
    ON parser_revision(statement_format_id, enabled, priority DESC, revision_number DESC);
CREATE INDEX idx_parser_revision_type_enabled ON parser_revision(parser_type, enabled);

-- Move existing CSV column configuration into parser revisions.
INSERT INTO parser_revision (
    statement_format_id,
    revision_number,
    parser_type,
    handler_key,
    config_schema_version,
    parser_config,
    priority,
    enabled,
    created_at,
    updated_at
)
SELECT
    id,
    1,
    'CSV_COLUMN_CONFIG',
    NULL,
    1,
    CONCAT(
        '{',
        '"dateHeader":', CASE WHEN date_header IS NULL THEN 'null' ELSE '"' || REPLACE(date_header, '"', '\"') || '"' END, ',',
        '"dateFormat":', CASE WHEN date_format IS NULL THEN 'null' ELSE '"' || REPLACE(date_format, '"', '\"') || '"' END, ',',
        '"descriptionHeader":', CASE WHEN description_header IS NULL THEN 'null' ELSE '"' || REPLACE(description_header, '"', '\"') || '"' END, ',',
        '"creditHeader":', CASE WHEN credit_header IS NULL THEN 'null' ELSE '"' || REPLACE(credit_header, '"', '\"') || '"' END, ',',
        '"debitHeader":', CASE WHEN debit_header IS NULL THEN 'null' ELSE '"' || REPLACE(debit_header, '"', '\"') || '"' END, ',',
        '"typeHeader":', CASE WHEN type_header IS NULL THEN 'null' ELSE '"' || REPLACE(type_header, '"', '\"') || '"' END, ',',
        '"categoryHeader":', CASE WHEN category_header IS NULL THEN 'null' ELSE '"' || REPLACE(category_header, '"', '\"') || '"' END,
        '}'
    ),
    0,
    enabled,
    COALESCE(created_at, CURRENT_TIMESTAMP),
    COALESCE(updated_at, CURRENT_TIMESTAMP)
FROM statement_format
WHERE format_type = 'CSV';

-- Represent static PDF extractors as parser revisions with internal handler keys.
INSERT INTO parser_revision (
    statement_format_id,
    revision_number,
    parser_type,
    handler_key,
    config_schema_version,
    parser_config,
    priority,
    enabled,
    created_at,
    updated_at
)
SELECT
    id,
    1,
    'STATIC_HANDLER',
    format_key,
    1,
    NULL,
    0,
    enabled,
    COALESCE(created_at, CURRENT_TIMESTAMP),
    COALESCE(updated_at, CURRENT_TIMESTAMP)
FROM statement_format
WHERE format_type = 'PDF';

-- Store import provenance by top-level statement format and parser revision.
ALTER TABLE file_import ADD COLUMN statement_format_id BIGINT;
ALTER TABLE file_import ADD COLUMN parser_revision_id BIGINT;
ALTER TABLE file_import ALTER COLUMN format DROP NOT NULL;

UPDATE file_import
SET statement_format_id = statement_format.id
FROM statement_format
WHERE file_import.format = statement_format.format_key;

UPDATE file_import
SET parser_revision_id = parser_revision.id
FROM parser_revision
WHERE file_import.statement_format_id = parser_revision.statement_format_id
  AND parser_revision.revision_number = 1;

ALTER TABLE file_import
    ADD CONSTRAINT fk_file_import_statement_format
        FOREIGN KEY (statement_format_id) REFERENCES statement_format(id);
ALTER TABLE file_import
    ADD CONSTRAINT fk_file_import_parser_revision
        FOREIGN KEY (parser_revision_id) REFERENCES parser_revision(id);

CREATE INDEX idx_file_import_statement_format ON file_import(statement_format_id);
CREATE INDEX idx_file_import_parser_revision ON file_import(parser_revision_id);

COMMENT ON COLUMN file_import.format IS
    'Legacy format key retained for imports created before statement_format_id provenance';
COMMENT ON COLUMN file_import.statement_format_id IS
    'Top-level statement format selected for this import; nullable only for legacy unmatched rows';
COMMENT ON COLUMN file_import.parser_revision_id IS
    'Parser revision that parsed this import; nullable only for legacy unmatched rows';

-- Remove parser configuration and public slug columns from the user-facing format table.
ALTER TABLE statement_format DROP COLUMN format_key;
ALTER TABLE statement_format DROP COLUMN date_header;
ALTER TABLE statement_format DROP COLUMN date_format;
ALTER TABLE statement_format DROP COLUMN description_header;
ALTER TABLE statement_format DROP COLUMN credit_header;
ALTER TABLE statement_format DROP COLUMN debit_header;
ALTER TABLE statement_format DROP COLUMN type_header;
ALTER TABLE statement_format DROP COLUMN category_header;
