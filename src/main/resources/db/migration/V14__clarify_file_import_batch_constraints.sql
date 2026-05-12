-- Clarify and reassert file import constraints used by token-backed batch imports.
--
-- transaction.file_import_id remains nullable for legacy/service-created transactions
-- that do not originate from an uploaded source file. Token-backed batch imports are
-- enforced in service code to link each created transaction to either a new
-- file_import row or the existing row for the same content hash and user.

ALTER TABLE file_import ALTER COLUMN content_hash SET NOT NULL;
ALTER TABLE file_import ALTER COLUMN original_filename SET NOT NULL;
ALTER TABLE file_import ALTER COLUMN format SET NOT NULL;
ALTER TABLE file_import ALTER COLUMN file_size_bytes SET NOT NULL;
ALTER TABLE file_import ALTER COLUMN transaction_count SET NOT NULL;
ALTER TABLE file_import ALTER COLUMN imported_by SET NOT NULL;
ALTER TABLE file_import ALTER COLUMN imported_at SET NOT NULL;

COMMENT ON COLUMN transaction.file_import_id IS
    'Source file import for token-backed batch imports; nullable only for legacy or service-created transactions without an uploaded source file';
