# Transaction Duplicate Detection

**Status:** Active
**Service:** transaction-service

## Overview

Duplicate detection applies to the file preview and batch import flow:

1. `POST /v1/transactions/preview` parses an ordered, non-empty collection of
   CSV or PDF files sharing one statement format and optional account, then
   marks likely duplicate rows for user review.
2. `POST /v1/transactions/batch` re-checks duplicates before persistence and
   skips matching rows unless the submitted row sets `allowDuplicate=true`.

Preview duplicate metadata is advisory. Batch import is authoritative because
persisted transactions can change after preview.

## Transaction Match Rule

Duplicate transaction matching is scoped to the authenticated owner. Different
users can import the same transaction independently.

The service first matches strict financial identity fields:

- `bankName`
- `date`
- `amount`
- `type`
- `currencyIsoCode`

Field rules:

- `accountId` is not part of duplicate matching. A transaction can be marked as
  a duplicate even when the preview row has a different account ID or one side
  has no account ID.
- `amount` is canonicalized to scale 2.
- Only active persisted transactions are candidates. Soft-deleted rows are
  ignored.

Preview rows and repository lookups use the same normalized financial identity
value for these fields. That identity is created once per row or database
candidate and is reused for service grouping and structured repository
parameters.

After the strict financial identity match, descriptions are compared in the
service layer:

- Descriptions match only when their normalized forms are equal.
- Normalization removes case, whitespace, punctuation, separators, and
  diacritic differences.
- Descriptions that are merely similar do not match.

## Preview Behavior

Preview never persists transactions. Each preview row includes:

- `duplicate=false` when no match is found.
- `duplicate=true` and `duplicateReason=EXISTING_TRANSACTION` when the row
  matches an active persisted transaction owned by the authenticated user.
- `duplicate=true` and `duplicateReason=IN_BATCH` when the row duplicates an
  earlier source file that completed successfully in the same preview request.

Rows are never compared with other rows from their own source file. A source's
rows enter the earlier-file candidate set only after the entire source has been
evaluated, so repeated rows faithfully present in one statement remain
unmarked. When a row matches both a persisted transaction and an earlier file,
`EXISTING_TRANSACTION` takes precedence. Persisted candidates are loaded once
for the complete grouped preview.

Multipart order determines response order and earlier-file precedence. The
preview endpoint stops at the first source failure, names that source in the
standard error response, and returns no partial preview body. It does not
return matching transaction IDs.

## Batch Behavior

Batch import accepts an ordered, non-empty collection of source file groups.
Every group contains its own preview token and reviewed rows. The controller
verifies every token for the authenticated owner before the persistence service
is called. All verified tokens must share one statement format ID and account
ID; parser revision IDs may differ.

The service validates every submitted row before database work, loads persisted
duplicate candidates once, re-runs duplicate detection, resolves per-file
provenance, and persists all accepted groups in one transaction. Batch request
order is authoritative for first-file-wins behavior. Business validation
errors use nested paths such as `files[1].transactions[4].date`, and their safe
messages identify the verified source filename.

If any persistence operation fails, all transactions and newly created
`file_import` rows attempted by the grouped request roll back together;
pre-existing rows remain unchanged.

Per-row duplicate handling:

- Omit `allowDuplicate` or set it to `false` for normal imports. Matching rows
  are skipped.
- Set `allowDuplicate=true` only for a row that should be intentionally
  imported despite matching duplicate detection.
- Rows are never compared with other rows from their own source file, so
  repeated rows within one faithful statement remain eligible.
- Rows accepted from a completed file, including rows accepted with
  `allowDuplicate=true`, are added to the earlier-file candidate set and can
  cause matching rows in later files to be skipped.
- Persisted matches are evaluated before earlier-file matches.

Batch responses include aggregate counts plus ordered per-file results. Each
file result contains its verified source filename, the same three per-file
counts, and that file's created transactions:

- `created` - Accepted rows created across all files.
- `duplicatesSkipped` - Rows skipped because they matched duplicate detection
  and `allowDuplicate` was false or omitted.
- `duplicatesImported` - Rows imported even though they matched duplicate
  detection because `allowDuplicate=true`.

A file may return `created=0` when another file creates transactions. That
zero-created file does not create provenance. If duplicate filtering leaves no
rows to create across the complete request, batch import fails with
`BATCH_IMPORT_NO_TRANSACTIONS_CREATED` and no new `file_import` row is recorded.

## File Reupload Tracking

Exact-file reupload status is separate from transaction duplicate detection.
The service computes a SHA-256 content hash for the uploaded bytes and checks it
against previous `file_import` records for the authenticated user.

Per-file preview response behavior:

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

Every file in a successful grouped preview has its own opaque
`previewImportToken`. Tokens are created from that file's hash, filename,
selected parser revision, and size; there is no combined token or content hash.
The batch endpoint accepts the ordered preview file results together and keeps
each token nested with that source's reviewed rows.

Token behavior:

- The token is encrypted and time-limited.
- The token carries source-file identity verified during preview: owner,
  content hash, original filename, statement format ID, parser revision ID,
  account ID, file size, and expiration timestamps.
- Clients must treat the token as opaque and must not decode it or derive source
  metadata from it.
- Missing, invalid, expired, incomplete, or wrong-owner tokens fail before the
  persistence service is called.
- All tokens are verified before grouped statement-format and account identity
  checks. Different parser revision IDs remain valid under the same public
  statement format.

For each source group with accepted rows, the service records source-file
metadata in a separate `file_import`. If the same `(content_hash, imported_by)`
already exists, that group's created rows link to the existing row instead of
creating a duplicate file import record. Created rows never link to another
source group's new provenance.

## Database Support

The `transaction` table has
`idx_transaction_owner_deleted_duplicate_candidates` for owner-scoped candidate
lookup across the normalized strict financial identity fields. The repository
query receives those identity values as structured parameter arrays; description
comparison stays in the service layer.

The `file_import` table has a unique index on `(content_hash, imported_by)` for
exact-file reupload tracking. `transaction.file_import_id` links created
token-backed batch transactions to their source import record.

## Related API Fields

- `PreviewResponse.files`
- `PreviewFileResponse.previewImportToken`
- `PreviewFileResponse.fileImport`
- `PreviewTransactionResponse.duplicate`
- `PreviewTransactionResponse.duplicateReason`
- `BatchImportTransactionRequest.allowDuplicate`
- `BatchImportRequest.files`
- `BatchImportFileRequest.previewImportToken`
- `BatchImportResponse.files`
- `BatchImportResponse.duplicatesSkipped`
- `BatchImportResponse.duplicatesImported`

## Related Errors

- `MISSING_ORIGINAL_FILENAME` - A preview upload part omitted the multipart
  filename or supplied only whitespace; the message identifies its ordered
  index.
- `PREVIEW_IMPORT_TOKEN_EXPIRED` - Batch submitted an expired token.
- `INVALID_REQUEST` - Verified batch tokens did not share one statement format
  and account identity.
- `BATCH_IMPORT_NO_TRANSACTIONS_CREATED` - No submitted rows remained after
  duplicate filtering or the request had no importable rows.
