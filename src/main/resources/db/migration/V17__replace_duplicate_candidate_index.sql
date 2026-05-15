-- Replace exact-description duplicate index with one optimized for description-free candidates.
DROP INDEX IF EXISTS idx_transaction_owner_deleted_duplicate_fields;

CREATE INDEX idx_transaction_owner_deleted_duplicate_candidates
    ON transaction (
        owner_id,
        deleted,
        account_id,
        bank_name,
        date,
        amount,
        type,
        currency_iso_code
    );

COMMENT ON INDEX idx_transaction_owner_deleted_duplicate_candidates IS
    'Composite index for owner-scoped duplicate candidate lookup before description matching';
