-- Remove legacy format-key provenance now that imports are tied to statement formats
-- and parser revisions by ID.
ALTER TABLE file_import DROP COLUMN format;
ALTER TABLE file_import ALTER COLUMN statement_format_id SET NOT NULL;
ALTER TABLE file_import ALTER COLUMN parser_revision_id SET NOT NULL;
