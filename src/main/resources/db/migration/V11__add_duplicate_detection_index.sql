-- Add composite index for efficient per-owner duplicate detection during batch import
-- The duplicate detection query filters by: owner_id, deleted, and matches on date|amount|description
-- This index optimizes the WHERE clause: owner_id = ? AND deleted = false AND CONCAT(...) IN (...)
CREATE INDEX idx_transaction_owner_deleted_date_amount_desc
    ON transaction(owner_id, deleted, date, amount, description);

-- Add comment for documentation
COMMENT ON INDEX idx_transaction_owner_deleted_date_amount_desc IS
    'Composite index for efficient per-owner duplicate detection during batch import';
