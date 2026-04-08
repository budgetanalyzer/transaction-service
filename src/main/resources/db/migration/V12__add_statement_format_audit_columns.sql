-- Add audit columns to statement_format for AuditableEntity support
ALTER TABLE statement_format ADD COLUMN created_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE statement_format ADD COLUMN updated_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE statement_format ADD COLUMN created_by VARCHAR(50);
ALTER TABLE statement_format ADD COLUMN updated_by VARCHAR(50);

-- Backfill seeded rows created before audit tracking existed
UPDATE statement_format
SET created_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP,
    created_by = 'SYSTEM',
    updated_by = 'SYSTEM'
WHERE created_at IS NULL;

ALTER TABLE statement_format ALTER COLUMN created_at SET NOT NULL;

COMMENT ON COLUMN statement_format.created_by IS
    'User ID who created this format (claims-header subject, or SYSTEM for Flyway-seeded rows)';
COMMENT ON COLUMN statement_format.updated_by IS
    'User ID who last modified this format (claims-header subject)';
