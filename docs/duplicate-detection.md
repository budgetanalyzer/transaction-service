# Transaction Duplicate Detection

**Status:** Active
**Service:** transaction-service

## Overview

Duplicate detection applies to the file preview and batch import flow:

1. `POST /v1/transactions/preview` parses a CSV or PDF file and marks likely
   duplicate rows for user review.
2. `POST /v1/transactions/batch` re-checks duplicates before persistence and
   skips matching rows unless the submitted row sets `allowDuplicate=true`.

Preview duplicate metadata is advisory. Batch import is authoritative because
persisted transactions can change after preview.

## Transaction Match Rule

Duplicate transaction matching is scoped to the authenticated owner. Different
users can import the same transaction independently.

The service first matches strict financial identity fields:

- `accountId`
- `bankName`
- `date`
- `amount`
- `type`
- `currencyIsoCode`

Field rules:

- `accountId` treats `null` and empty string as equivalent.
- `amount` is canonicalized to scale 2.
- Only active persisted transactions are candidates. Soft-deleted rows are
  ignored.

After the strict financial identity match, descriptions are compared in the
service layer:

- Normalized exact match removes case, whitespace, punctuation, separators, and
  diacritic differences.
- Conservative fuzzy match uses normalized Levenshtein similarity and requires
  both normalized descriptions to be at least 8 characters.
- Fuzzy matches require at least 0.90 similarity.
- If either original description contains numeric tokens, both ordered numeric
  token lists must match exactly. This prevents fuzzy matching across different
  references, check numbers, or card suffixes.

## Preview Behavior

Preview never persists transactions. Each preview row includes:

- `duplicate=false` when no match is found.
- `duplicate=true` and `duplicateReason=EXISTING_TRANSACTION` when the row
  matches an active persisted transaction owned by the authenticated user.
- `duplicate=true` and `duplicateReason=IN_BATCH` when the row duplicates an
  earlier row in the same preview response.

The preview endpoint does not return matching transaction IDs.

## Batch Behavior

Batch import validates the submitted rows, re-runs duplicate detection, and then
persists the accepted rows in one transaction.

Per-row duplicate handling:

- Omit `allowDuplicate` or set it to `false` for normal imports. Matching rows
  are skipped.
- Set `allowDuplicate=true` only for a row that should be intentionally
  imported despite matching duplicate detection.
- Rows skipped because they duplicate existing persisted transactions are not
  added to the in-batch candidate set for later submitted rows.
- Rows accepted for creation, including rows accepted with
  `allowDuplicate=true`, are added to the in-batch candidate set and can cause
  later rows to be skipped.

Batch responses include:

- `duplicatesSkipped` - Rows skipped because they matched duplicate detection
  and `allowDuplicate` was false or omitted.
- `duplicatesImported` - Rows imported even though they matched duplicate
  detection because `allowDuplicate=true`.

If duplicate filtering leaves no rows to create, batch import fails with
`BATCH_IMPORT_NO_TRANSACTIONS_CREATED` and no `file_import` row is recorded.

## File Reupload Tracking

Exact-file reupload status is separate from transaction duplicate detection.
The service computes a SHA-256 content hash for the uploaded bytes and checks it
against previous `file_import` records for the authenticated user.

Preview response behavior:

- `fileImport.alreadyImported=false` when the file bytes have not been recorded
  for the current user.
- `fileImport.alreadyImported=true` when the same file bytes were previously
  recorded for the current user.
- `fileImport.warningCode=FILE_ALREADY_IMPORTED` and `previousImport` metadata
  are included for exact reuploads.
- The API never exposes the raw content hash.
- The legacy top-level `warnings` array is not part of the preview response.

File reupload status does not block preview or batch import. Transaction
duplicate rules remain authoritative.

## Preview Import Token

Every successful preview returns an opaque `previewImportToken`. Batch import
requires this token so the service can record file-backed import metadata and
link created transactions to a `file_import` row.

Token behavior:

- The token is encrypted and time-limited.
- The token carries source-file identity verified during preview: owner,
  content hash, original filename, statement format ID, parser revision ID,
  account ID, file size, and expiration timestamps.
- Clients must treat the token as opaque and must not decode it or derive source
  metadata from it.
- Missing, invalid, expired, incomplete, or wrong-owner tokens fail before
  service-layer validation, duplicate checks, or persistence.

When at least one transaction is created, the service records source-file
metadata in `file_import`. If the same `(content_hash, imported_by)` already
exists, the batch links created rows to that existing row instead of creating a
duplicate file import record.

## Database Support

The `transaction` table has
`idx_transaction_owner_deleted_duplicate_candidates` for owner-scoped candidate
lookup across strict financial identity fields. Description comparison stays in
the service layer.

The `file_import` table has a unique index on `(content_hash, imported_by)` for
exact-file reupload tracking. `transaction.file_import_id` links created
token-backed batch transactions to their source import record.

## Related API Fields

- `PreviewResponse.previewImportToken`
- `PreviewResponse.fileImport`
- `PreviewTransactionResponse.duplicate`
- `PreviewTransactionResponse.duplicateReason`
- `BatchImportTransactionRequest.allowDuplicate`
- `BatchImportResponse.duplicatesSkipped`
- `BatchImportResponse.duplicatesImported`

## Related Errors

- `MISSING_ORIGINAL_FILENAME` - Preview upload omitted the multipart filename
  or supplied only whitespace.
- `PREVIEW_IMPORT_TOKEN_EXPIRED` - Batch submitted an expired token.
- `BATCH_IMPORT_NO_TRANSACTIONS_CREATED` - No submitted rows remained after
  duplicate filtering or the request had no importable rows.
