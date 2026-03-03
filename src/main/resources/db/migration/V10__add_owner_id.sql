-- Add owner_id column to transaction table for resource-level authorization
ALTER TABLE transaction ADD COLUMN owner_id VARCHAR(50);

-- Backfill owner_id from created_by (populated by JPA auditing since V3)
-- For any rows where created_by is null, use 'migration' as fallback
UPDATE transaction SET owner_id = COALESCE(created_by, 'migration') WHERE owner_id IS NULL;

-- Make owner_id non-nullable after backfill
ALTER TABLE transaction ALTER COLUMN owner_id SET NOT NULL;

-- Index for efficient owner-scoped queries
CREATE INDEX idx_transaction_owner_id ON transaction(owner_id);
