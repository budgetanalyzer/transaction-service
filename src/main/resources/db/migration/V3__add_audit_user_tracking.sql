-- Add user tracking columns for audit trail
-- These columns track who created and last modified each transaction

-- Add created_by column
ALTER TABLE transaction ADD COLUMN created_by VARCHAR(50);

-- Add updated_by column
ALTER TABLE transaction ADD COLUMN updated_by VARCHAR(50);

-- Add indexes for common query patterns (e.g., "show all transactions created by user X")
CREATE INDEX idx_transaction_created_by ON transaction(created_by);
CREATE INDEX idx_transaction_updated_by ON transaction(updated_by);

-- Add comments for documentation
COMMENT ON COLUMN transaction.created_by IS 'User ID who created this transaction (Auth0 sub claim)';
COMMENT ON COLUMN transaction.updated_by IS 'User ID who last modified this transaction (Auth0 sub claim)';
