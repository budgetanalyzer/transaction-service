-- Seed Bangkok Bank statement PDF format metadata.
-- PDF extraction is handled by BangkokBankStatementPdfExtractor.
INSERT INTO statement_format (
    format_key,
    format_type,
    bank_name,
    default_currency_iso_code,
    display_name,
    created_at,
    updated_at,
    created_by,
    updated_by
)
VALUES (
    'bkk-bank-statement-pdf',
    'PDF',
    'Bangkok Bank',
    'THB',
    'Bangkok Bank - Statement PDF',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'SYSTEM',
    'SYSTEM'
);
