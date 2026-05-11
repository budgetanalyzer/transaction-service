-- Replace the initial duplicate-detection index with one that covers the expanded key:
-- account_id, bank_name, date, amount, type, currency_iso_code, and description.
DROP INDEX IF EXISTS idx_transaction_owner_deleted_date_amount_desc;

CREATE INDEX idx_transaction_owner_deleted_duplicate_fields
    ON transaction (
        owner_id,
        deleted,
        account_id,
        bank_name,
        date,
        amount,
        type,
        currency_iso_code,
        description
    );

COMMENT ON INDEX idx_transaction_owner_deleted_duplicate_fields IS
    'Composite index for owner-scoped duplicate detection using the expanded transaction key';
