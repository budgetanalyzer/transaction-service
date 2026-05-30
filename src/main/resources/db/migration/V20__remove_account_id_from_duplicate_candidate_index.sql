-- Duplicate detection no longer requires account_id to match.
DROP INDEX IF EXISTS idx_transaction_owner_deleted_duplicate_candidates;

CREATE INDEX idx_transaction_owner_deleted_duplicate_candidates
    ON transaction (
        owner_id,
        deleted,
        bank_name,
        date,
        amount,
        type,
        currency_iso_code
    );

COMMENT ON INDEX idx_transaction_owner_deleted_duplicate_candidates IS
    'Composite index for owner-scoped duplicate candidate lookup before description matching';
