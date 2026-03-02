-- Add display_name column for user-friendly format names
ALTER TABLE statement_format ADD COLUMN display_name VARCHAR(100);

-- Rename CSV format keys to include format type suffix
UPDATE statement_format SET format_key = 'capital-one-bank-csv' WHERE format_key = 'capital-one';
UPDATE statement_format SET format_key = 'capital-one-credit-csv' WHERE format_key = 'capital-one-ytd';
UPDATE statement_format SET format_key = 'bkk-bank-csv' WHERE format_key = 'bkk-bank';
UPDATE statement_format SET format_key = 'bkk-bank-statement-csv' WHERE format_key = 'bkk-bank-statement';
UPDATE statement_format SET format_key = 'truist-bank-csv' WHERE format_key = 'truist';

-- Rename PDF format keys
UPDATE statement_format SET format_key = 'capital-one-credit-monthly-statement' WHERE format_key = 'capital-one-monthly';
UPDATE statement_format SET format_key = 'capital-one-credit-yearly-statement' WHERE format_key = 'capital-one-yearly';

-- Set display names for CSV formats
UPDATE statement_format SET display_name = 'Capital One Bank - Export' WHERE format_key = 'capital-one-bank-csv';
UPDATE statement_format SET display_name = 'Capital One Credit - Export' WHERE format_key = 'capital-one-credit-csv';
UPDATE statement_format SET display_name = 'Bangkok Bank - Export' WHERE format_key = 'bkk-bank-csv';
UPDATE statement_format SET display_name = 'Bangkok Bank - Statement' WHERE format_key = 'bkk-bank-statement-csv';
UPDATE statement_format SET display_name = 'Truist Bank - Export' WHERE format_key = 'truist-bank-csv';

-- Set display names for PDF formats
UPDATE statement_format SET display_name = 'Capital One Credit - Monthly Statement' WHERE format_key = 'capital-one-credit-monthly-statement';
UPDATE statement_format SET display_name = 'Capital One Credit - Yearly Statement' WHERE format_key = 'capital-one-credit-yearly-statement';

-- Add new Capital One bank monthly statement PDF format
INSERT INTO statement_format (format_key, format_type, bank_name, default_currency_iso_code, display_name)
VALUES ('capital-one-bank-monthly-statement', 'PDF', 'Capital One', 'USD', 'Capital One Bank - Monthly Statement');

-- Make display_name NOT NULL after populating
ALTER TABLE statement_format ALTER COLUMN display_name SET NOT NULL;
