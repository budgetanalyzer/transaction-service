-- Initial schema for Budget Analyzer API
-- This migration represents the baseline schema from the Transaction entity

-- Create transaction table
CREATE TABLE transaction (
    id BIGSERIAL PRIMARY KEY,
    account_id VARCHAR(255),
    bank_name VARCHAR(255) NOT NULL,
    date DATE NOT NULL,
    currency_iso_code VARCHAR(3) NOT NULL,
    amount NUMERIC(38, 2) NOT NULL,
    type VARCHAR(20) NOT NULL,
    description TEXT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE,
    deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMP(6) WITH TIME ZONE
);

-- Create indexes for common query patterns
CREATE INDEX idx_transaction_account_id ON transaction(account_id);
CREATE INDEX idx_transaction_bank_name ON transaction(bank_name);
CREATE INDEX idx_transaction_date ON transaction(date);
CREATE INDEX idx_transaction_currency_iso_code ON transaction(currency_iso_code);
CREATE INDEX idx_transaction_type ON transaction(type);
CREATE INDEX idx_transaction_deleted ON transaction(deleted);

-- Add comments for documentation
COMMENT ON TABLE transaction IS 'Stores financial transactions imported from various bank CSV files';
COMMENT ON COLUMN transaction.id IS 'Primary key, auto-generated';
COMMENT ON COLUMN transaction.account_id IS 'Optional account identifier for multi-account support';
COMMENT ON COLUMN transaction.bank_name IS 'Name of the bank where the transaction occurred';
COMMENT ON COLUMN transaction.date IS 'Date when the transaction occurred';
COMMENT ON COLUMN transaction.currency_iso_code IS 'ISO 4217 three-letter currency code (e.g., USD, THB, CAD)';
COMMENT ON COLUMN transaction.amount IS 'Transaction amount with 2 decimal precision';
COMMENT ON COLUMN transaction.type IS 'Transaction type (CREDIT or DEBIT)';
COMMENT ON COLUMN transaction.description IS 'Transaction description from bank statement';
COMMENT ON COLUMN transaction.created_at IS 'Timestamp with timezone when the record was created (UTC)';
COMMENT ON COLUMN transaction.updated_at IS 'Timestamp with timezone when the record was last updated (UTC)';
COMMENT ON COLUMN transaction.deleted IS 'Soft-delete flag - true if the transaction has been deleted';
COMMENT ON COLUMN transaction.deleted_at IS 'Timestamp with timezone when the record was soft-deleted (null if not deleted)';
