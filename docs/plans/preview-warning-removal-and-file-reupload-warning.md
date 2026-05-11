# Preview Warning Removal and File Reupload Warning

## Context

`PreviewWarning` was introduced for an older direction where the service might
accept unspecified PDF formats and perform fuzzy field or column mapping. That
direction is no longer active. Current preview extraction uses explicit
statement formats, and production extractors always return empty warning lists.

The existing warning model is row/field-oriented:

```json
{
  "index": 12,
  "field": "amount",
  "message": "OCR confidence low"
}
```

That shape does not fit the duplicate-detection plan TODO for exact file
reuploads. "This uploaded file has already been imported" is file-level
metadata, not a warning about a parsed transaction row or field.

## Goals

- Remove preview warnings from all current API, service, extractor, test, and
  documentation flows.
- Keep transaction-level duplicate preview metadata on each
  `PreviewTransactionResponse`.
- Add a plan for warning the UI when the exact uploaded file bytes have already
  been imported by the current user.
- Keep exact-file reupload detection separate from transaction duplicate
  detection.
- Avoid text-only response contracts where the UI needs stable behavior.

## Non-Goals

- Do not reintroduce fuzzy PDF or fuzzy column mapping.
- Do not use file reupload detection as a substitute for transaction duplicate
  detection.
- Do not expose matching transaction IDs from duplicate detection.
- Do not expose the file content hash unless there is a clear client need.

## Clarifying Questions

1. Is it acceptable for the preview response contract to remove `warnings`
   outright, or do any deployed clients still expect the field to exist as an
   empty array?
2. Should exact-file reupload remain preview-only advisory, or should the batch
   import path also reject or require override for a file that preview reported
   as already imported?
3. Should the UI receive previous import metadata such as original filename,
   imported timestamp, format, account ID, and transaction count, or only a
   stable code and boolean?
4. Is the preview-to-batch flow allowed to grow source-file metadata, such as a
   preview token or file hash reference, so successful batch imports can record
   `file_import` rows for future exact-file detection?

## Current Code Impact

Remove these warning-specific types:

- `src/main/java/org/budgetanalyzer/transaction/api/response/PreviewWarning.java`
- `src/main/java/org/budgetanalyzer/transaction/service/dto/PreviewWarning.java`

Update these response and service DTOs:

- `PreviewResponse`: remove `List<PreviewWarning> warnings`.
- `PreviewResult`: remove `List<PreviewWarning> warnings`.

Update extractor contracts:

- Remove warnings from `StatementExtractor.ExtractionResult`, or remove
  `ExtractionResult` entirely and have `extract(...)` return
  `List<PreviewTransaction>`.
- Prefer returning `List<PreviewTransaction>` directly unless another concrete
  extractor-level metadata field is needed now. Keeping a one-field wrapper is
  unnecessary ceremony.

Update implementations:

- `ConfigurableCsvStatementExtractor`
- `CapitalOneBankMonthlyStatementExtractor`
- `CapitalOneCreditMonthlyStatementExtractor`
- `CapitalOneCreditYearlySummaryExtractor`
- Any tests that construct `StatementExtractor.ExtractionResult`

Update controller and OpenAPI docs:

- Remove "parsing warnings" wording from `TransactionController`.
- Remove `warnings: []` from API examples in `docs/api/README.md` and
  `docs/csv-import.md`.
- Update controller tests that assert `$.warnings` is empty.
- Update OpenAPI schema tests if they assert the old warning schema.

## File Reupload Warning Shape

The reupload signal should be file-level and typed. The best fit is a dedicated
field on `PreviewResponse`, not `warnings[]`.

Recommended shape:

```json
{
  "sourceFile": "statement.csv",
  "detectedFormat": "capital-one",
  "fileImport": {
    "alreadyImported": true,
    "warningCode": "FILE_ALREADY_IMPORTED",
    "message": "This exact file was previously imported.",
    "previousImport": {
      "originalFilename": "statement.csv",
      "importedAt": "2026-05-01T12:34:56Z",
      "format": "capital-one",
      "accountId": "checking-12345",
      "transactionCount": 42
    }
  },
  "transactions": []
}
```

If no previous import exists:

```json
{
  "fileImport": {
    "alreadyImported": false,
    "warningCode": null,
    "message": null,
    "previousImport": null
  }
}
```

An alternative is to make `fileImport` nullable when no warning exists:

```json
{
  "fileImport": null
}
```

The non-null object is more explicit and easier for clients that prefer a stable
field. The nullable object is smaller but requires null handling and makes the
field read more like an optional warning than preview status.

## Text Field vs Enum Code

### Text-only field

Example:

```json
{
  "fileWarning": "This exact file was previously imported."
}
```

Pros:

- Smallest API change.
- Easy to display directly.

Cons:

- Brittle for UI behavior because clients must parse or compare prose.
- Harder to localize.
- Harder to test precisely.
- Message changes become accidental API changes.
- Does not carry structured previous import metadata.

Text-only is acceptable only if the UI will never branch on the warning and
only displays whatever the service sends.

### Enum-only field

Example:

```json
{
  "fileWarningCode": "FILE_ALREADY_IMPORTED"
}
```

Pros:

- Stable contract for UI behavior.
- Easy to test.
- Leaves display copy to the frontend.

Cons:

- Too sparse if the UI needs to show when the prior import happened or what it
  was called.
- Requires clients to map every code to text.

Enum-only is better than text-only, but incomplete if previous import context is
useful to users.

### Enum plus metadata

Example:

```json
{
  "fileImport": {
    "alreadyImported": true,
    "warningCode": "FILE_ALREADY_IMPORTED",
    "previousImport": {
      "originalFilename": "statement.csv",
      "importedAt": "2026-05-01T12:34:56Z",
      "transactionCount": 42
    }
  }
}
```

Pros:

- Stable for client behavior.
- Clear enough for frontend display.
- Extensible if a second file-level preview notice is added later.
- Does not overload row-level transaction duplicate metadata.

Cons:

- More DTOs and OpenAPI schema surface.
- Requires a decision about how much previous import metadata to expose.

This is the recommended approach.

## Is This Warning Shape Unique?

The exact-file reupload warning appears unique in the current service because it
is about the uploaded file as a whole. Most other preview concerns do not fit
this shape:

- Transaction duplicates already fit per-row metadata on
  `PreviewTransactionResponse`.
- In-preview duplicates also fit per-row metadata.
- Validation failures should remain errors, not warnings.
- Parser failures should remain errors, not warnings.
- Fuzzy field or OCR confidence warnings were the original row/field use case,
  but that product direction is no longer active.

A generic `warnings[]` array would only make sense if there are multiple
current non-blocking preview concerns with a shared shape. There are not.

`FilePreviewWarning` is a reasonable name if the API wants to frame this as a
warning. However, `FileImportPreviewStatus` or `PreviewFileImportStatus` is more
accurate because the object describes import history status, not arbitrary file
health.

## Recommended API Model

Use these API DTOs:

```java
public record PreviewResponse(
    String sourceFile,
    String detectedFormat,
    PreviewFileImportStatus fileImport,
    List<PreviewTransactionResponse> transactions) {}

public record PreviewFileImportStatus(
    boolean alreadyImported,
    PreviewFileWarningCode warningCode,
    String message,
    PreviousFileImportResponse previousImport) {}

public enum PreviewFileWarningCode {
  FILE_ALREADY_IMPORTED
}

public record PreviousFileImportResponse(
    String originalFilename,
    Instant importedAt,
    String format,
    String accountId,
    Integer transactionCount) {}
```

`message` is optional. If included, clients should treat it as display copy, not
as a behavior switch. The stable switch is `warningCode`.

## Service Design

Preview flow:

1. Read uploaded bytes once in `TransactionImportService.previewWithExtractor`.
2. Compute a SHA-256 hash from those bytes, or add a `FileHashService` overload
   that accepts `byte[]` to avoid reading the multipart stream twice.
3. Query `file_import` by `(content_hash, imported_by)` using the current user.
4. Build `PreviewFileImportStatus`.
5. Extract transactions and mark transaction duplicates exactly as today.
6. Return the file import status alongside preview transactions.

Existing support:

- `FileImportTrackingService.checkFile(...)` already computes a hash and looks
  up prior imports.
- `FileImportRepository.findByContentHashAndImportedBy(...)` already supports
  the lookup.

Gap:

- Current `/batch` imports do not record `file_import` rows. `TransactionService`
  maps batch imports with `fileImport = null`, and `BatchImportRequest` does not
  carry source-file identity. The preview warning can only be reliable for files
  that have already been recorded in `file_import`.

## Recording File Imports

There are two viable paths.

### Option A: Preview warning only

Implement the preview lookup against existing `file_import` rows and do not
change `/batch`.

Pros:

- Smallest implementation.
- Satisfies the response-shape part of the TODO.

Cons:

- Incomplete if the normal preview-to-batch flow never records `file_import`.
- The warning may never trigger for newly imported files unless another import
  path writes `file_import`.

This option is weak unless production data already contains `file_import`
records from an older path.

### Option B: Add preview source identity to batch import

Have preview return a short-lived source identity, then require batch import to
submit it back. On successful batch import, record `file_import`.

Possible response addition:

```json
{
  "previewSource": {
    "contentHash": "server-managed-or-tokenized-value",
    "sourceFile": "statement.csv",
    "fileSizeBytes": 12345
  }
}
```

A raw content hash would work technically, but exposing hashes can create
unnecessary API surface. A server-side preview token is cleaner if token storage
already exists. Without storage, returning the hash is simpler but should be
treated as an implementation detail, not user-facing data.

Pros:

- Makes exact-file warning reliable for the normal workflow.
- Preserves traceability through `file_import_id` if transaction creation is
  later linked to file imports.

Cons:

- Larger API change.
- Requires deciding how long preview source identity is valid.
- Requires batch import to validate that submitted source metadata matches the
  authenticated user and imported rows.

### Option C: Reintroduce direct file import

Add or restore a direct file-import endpoint that parses and persists in one
request, records `file_import`, and rejects exact reuploads.

Pros:

- File import tracking is straightforward because the file and created
  transactions are in one request.

Cons:

- Does not match the current preview-edit-batch workflow.
- Duplicates import behavior across endpoints.

This is not recommended unless the product wants direct import again.

## Implementation Steps

1. Remove preview warnings from DTOs and extractor contracts.
2. Update all extractors to return only preview transactions.
3. Update `TransactionImportService` to stop carrying extractor warnings.
4. Update `PreviewResponse` and API examples to remove `warnings`.
5. Update tests that construct extraction results or assert `warnings`.
6. Introduce `PreviewFileImportStatus`, `PreviewFileWarningCode`, and
   `PreviousFileImportResponse`.
7. Inject file import tracking or repository/hash services into preview flow.
8. Compute the uploaded file hash during preview and query previous imports for
   the current user.
9. Populate `fileImport` in the preview response.
10. Add service and controller tests for both prior-import and no-prior-import
    preview responses.
11. Update OpenAPI schema tests and docs.
12. Decide and implement a recording strategy for `file_import`; prefer Option B
    if the warning is expected to work for the normal preview-to-batch flow.

## Test Plan

- Unit test preview extraction no longer exposes warnings.
- Unit test each extractor returns preview transactions directly.
- Controller test verifies preview response has no `warnings` field.
- Controller/OpenAPI test verifies `fileImport` schema.
- Service test verifies `fileImport.alreadyImported=false` when no prior record
  exists.
- Service test verifies `fileImport.alreadyImported=true` and previous import
  metadata when the same user previously imported the exact file bytes.
- Service test verifies another user's matching file hash does not trigger a
  warning.
- If batch recording is implemented, integration test that a successful batch
  import records `file_import` and a later preview of the same bytes reports the
  warning.

## Documentation Updates

- `docs/api/README.md`: remove `warnings`, document `fileImport`.
- `docs/csv-import.md`: remove `warnings`, document exact-file reupload status.
- `docs/database-schema.md`: confirm how `file_import` is populated and queried.
- `docs/plans/duplicate-detection-enhancements.md`: replace the TODO with a
  link to this plan once implementation begins or completes.

