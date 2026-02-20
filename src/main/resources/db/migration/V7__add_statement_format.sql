-- Create statement_format table for database-driven format configuration
CREATE TABLE statement_format (
    id BIGSERIAL PRIMARY KEY,
    format_key VARCHAR(50) NOT NULL UNIQUE,
    format_type VARCHAR(10) NOT NULL,
    bank_name VARCHAR(100) NOT NULL,
    default_currency_iso_code VARCHAR(3) NOT NULL,
    -- CSV-specific fields (null for PDF/XLSX)
    date_header VARCHAR(50),
    date_format VARCHAR(50),
    description_header VARCHAR(50),
    credit_header VARCHAR(50),
    debit_header VARCHAR(50),
    type_header VARCHAR(50),
    category_header VARCHAR(50),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    -- Constraints
    CONSTRAINT chk_format_type CHECK (format_type IN ('CSV', 'PDF', 'XLSX'))
);

-- Create index for common queries
CREATE INDEX idx_statement_format_enabled ON statement_format(enabled);
CREATE INDEX idx_statement_format_type_enabled ON statement_format(format_type, enabled);

-- Seed existing CSV formats from YAML configuration
INSERT INTO statement_format (format_key, format_type, bank_name, default_currency_iso_code,
    date_header, date_format, description_header, credit_header, debit_header, type_header, category_header)
VALUES
    ('capital-one', 'CSV', 'Capital One', 'USD',
     'Transaction Date', 'MM/dd/uu', 'Transaction Description',
     'Transaction Amount', 'Transaction Amount', 'Transaction Type', NULL),

    ('capital-one-ytd', 'CSV', 'Capital One', 'USD',
     'Date', 'MM/dd/uuuu', 'Description',
     'Amount', 'Amount', NULL, 'Category'),

    ('bkk-bank', 'CSV', 'Bangkok Bank', 'THB',
     'Date', 'd MMM uuuu HH:mm', 'Description',
     'Credit', 'Debit', NULL, NULL),

    ('bkk-bank-statement', 'CSV', 'Bangkok Bank', 'THB',
     'Date', 'dd/MM/yy', 'Particulars',
     'Deposit', 'Withdrawal', NULL, NULL),

    ('truist', 'CSV', 'Truist', 'CAD',
     'Transaction Date', 'MM/dd/uuuu', 'Description',
     'Amount', 'Amount', 'Transaction Type', NULL);

-- Seed existing PDF formats (metadata only - extraction handled by dedicated extractors)
INSERT INTO statement_format (format_key, format_type, bank_name, default_currency_iso_code)
VALUES
    ('capital-one-monthly', 'PDF', 'Capital One', 'USD'),
    ('capital-one-yearly', 'PDF', 'Capital One', 'USD');
