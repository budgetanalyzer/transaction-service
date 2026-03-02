-- Add foreign key from transaction to file_import for traceability
-- Nullable because existing transactions won't have a file import reference

ALTER TABLE transaction
    ADD COLUMN file_import_id BIGINT REFERENCES file_import(id);

CREATE INDEX idx_transaction_file_import_id ON transaction(file_import_id);
