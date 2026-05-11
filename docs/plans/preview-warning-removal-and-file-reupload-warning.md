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

## Decisions

- Remove the existing `warnings` field outright. Keeping an always-empty field
  would preserve a misleading contract for a feature direction that is no
  longer active.
- Use enum plus metadata for exact-file reupload information.
- Do not include backend-owned display text in the preview response. The UI
  should map stable enum codes and structured metadata to copy.
- Keep exact-file reupload detection advisory in preview. `/batch` should not
  reject solely because the same file was previously imported.
- Return previous import metadata: original filename, imported timestamp,
  format, account ID, and transaction count.
- Do not return the raw content hash.
- Add source identity to the preview-to-batch flow so successful batch imports
  can record `file_import` rows. Use an opaque signed token rather than exposing
  the file hash.

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
  "previewImportToken": "opaque-signed-token",
  "fileImport": {
    "alreadyImported": true,
    "warningCode": "FILE_ALREADY_IMPORTED",
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

Text-only would be acceptable only if the UI never branched on the warning and
only displayed whatever the service sent. That is not the desired contract here.

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

Enum-only is better than text-only, but incomplete because previous import
context is useful to users.

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

This is the selected approach. Do not include a backend `message` field; clients
should use `warningCode` for behavior and `previousImport` for display context.

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
    String previewImportToken,
    PreviewFileImportStatus fileImport,
    List<PreviewTransactionResponse> transactions) {}

public record PreviewFileImportStatus(
    boolean alreadyImported,
    PreviewFileWarningCode warningCode,
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

`warningCode` is nullable when `alreadyImported` is `false`.
`previewImportToken` is submitted back to `/batch` so the service can record a
successful file-backed batch import without exposing the raw content hash.

## Service Design

Preview flow:

1. Read uploaded bytes once in `TransactionImportService.previewWithExtractor`.
2. Compute a SHA-256 hash from those bytes, or add a `FileHashService` overload
   that accepts `byte[]` to avoid reading the multipart stream twice.
3. Query `file_import` by `(content_hash, imported_by)` using the current user.
4. Build `PreviewFileImportStatus`.
5. Build an opaque signed `previewImportToken` containing the current user ID,
   content hash, original filename, detected format, account ID, file size,
   issued time, and expiration time.
6. Extract transactions and mark transaction duplicates exactly as today.
7. Return the file import status, preview import token, and preview
   transactions.

Existing support:

- `FileImportTrackingService.checkFile(...)` already computes a hash and looks
  up prior imports.
- `FileImportRepository.findByContentHashAndImportedBy(...)` already supports
  the lookup.

Current gap:

- Current `/batch` imports do not record `file_import` rows. `TransactionService`
  maps batch imports with `fileImport = null`, and `BatchImportRequest` does not
  carry source-file identity. The preview warning can only be reliable for files
  that have already been recorded in `file_import`.

## Recording File Imports

Use the preview-to-batch source identity path.

Rejected alternatives:

- Preview warning only: too weak, because the normal preview-to-batch flow would
  not populate `file_import`.
- Direct file import endpoint: does not match the current review/edit/batch
  workflow and would duplicate import behavior.
- Raw content hash in API: technically simple, but exposes implementation
  detail with no user-facing value.

Recommended batch behavior:

- Add optional `previewImportToken` to `BatchImportRequest`.
- If the token is absent, keep current batch behavior and do not record
  `file_import`.
- If the token is present, verify signature, expiration, and owner before
  import.
- The exact-file check remains advisory. If a previous `file_import` already
  exists for the token hash and user, do not reject the batch solely for that
  reason. Continue to rely on transaction duplicate detection and
  `allowDuplicate`.
- If no previous `file_import` exists, create a `file_import` row for the
  successful batch and link newly created transactions to it.
- If all rows are skipped as transaction duplicates, do not create a
  `file_import` row because no transactions were imported. This keeps
  `transaction_count` meaningful.

## Future Implementation Steps

### Phase 1: Remove Legacy Preview Warnings

1. Read `../service-common/docs/code-quality-standards.md` before changing Java
   code.
2. Delete `api/response/PreviewWarning.java`.
3. Delete `service/dto/PreviewWarning.java`.
4. Change `PreviewResult` to contain only `sourceFile`, `detectedFormat`, and
   `transactions` for this phase. Later phases add file import status and the
   preview import token.
5. Change `PreviewResponse` to remove `warnings`.
6. Change `StatementExtractor.extract(...)` to return
   `List<PreviewTransaction>` directly.
7. Remove `StatementExtractor.ExtractionResult` if it has no remaining fields.
8. Update all extractor implementations to return transaction lists directly:
   `ConfigurableCsvStatementExtractor`,
   `CapitalOneBankMonthlyStatementExtractor`,
   `CapitalOneCreditMonthlyStatementExtractor`, and
   `CapitalOneCreditYearlySummaryExtractor`.
9. Update `TransactionImportService.previewWithExtractor(...)` to stop carrying
   extractor warnings.
10. Update tests that construct `ExtractionResult` or assert `$.warnings`.
11. Update `TransactionController` operation text to remove "parsing warnings".
12. Update `docs/api/README.md` and `docs/csv-import.md` examples to remove
    `warnings`.

### Phase 2: Add File Import Preview Status

1. Add service DTOs for file import preview status:
   `PreviewFileImportStatus`, `PreviewFileWarningCode`, and
   `PreviousFileImport`.
2. Add API response DTOs for the same contract:
   `PreviewFileImportStatusResponse`, `PreviewFileWarningCode`, and
   `PreviousFileImportResponse`, or use names consistent with existing response
   DTO conventions.
3. Add a `FileHashService.computeHash(byte[] fileContent)` overload so preview
   can hash the bytes it already read.
4. Inject the file import lookup dependency into `TransactionImportService`.
   Prefer using `FileImportTrackingService` if its API is adjusted to accept a
   precomputed hash or `byte[]`; otherwise inject `FileImportRepository` and
   `FileHashService`.
5. During preview, query `file_import` by content hash and current user.
6. Populate `fileImport.alreadyImported=false` with null `warningCode` and null
   `previousImport` when no prior import exists.
7. Populate `fileImport.alreadyImported=true`,
   `warningCode=FILE_ALREADY_IMPORTED`, and previous import metadata when a
   prior import exists.
8. Keep transaction duplicate marking unchanged.

### Phase 3: Add Opaque Preview Import Token

1. Add `PreviewImportTokenService`.
2. Use Java standard crypto (`HmacSHA256`) and Base64 URL encoding unless an
   existing service-common token utility is available.
3. Add configuration for token signing secret and token TTL. Document the env
   vars or properties in `README.md` or the nearest configuration doc.
4. Token payload should include owner ID, content hash, original filename,
   detected format, account ID, file size, issued time, and expiration time.
5. Token verification must reject invalid signature, expired token, missing
   required fields, and owner mismatch.
6. Return `previewImportToken` on `PreviewResponse`.
7. Do not include the raw content hash anywhere in the API response.

### Phase 4: Record File Imports During Batch

1. Add optional `previewImportToken` to `BatchImportRequest`.
2. Convert the verified token to a service-layer file import source DTO before
   calling service logic.
3. Add a `TransactionService.batchImport(...)` overload or command object that
   accepts transactions, user ID, and optional file import source.
4. Keep existing no-token batch behavior unchanged for manual or non-file batch
   imports.
5. During batch import, perform existing business validation and transaction
   duplicate filtering first.
6. If the token is present and at least one transaction will be created, check
   whether `file_import` already has a row for the token hash and user.
7. If a prior file import exists, continue without creating another
   `file_import` row; transaction duplicate rules remain authoritative.
8. If no prior file import exists, call `FileImportTrackingService.recordImport`
   with token metadata and the number of transactions that will be created.
9. Set the returned `FileImport` on newly created `Transaction` entities before
   `saveAll`.
10. Keep all batch persistence in one transaction.

### Phase 5: Documentation Cleanup

1. Update `docs/api/README.md` preview and batch sections with the new response
   and request fields.
2. Update `docs/csv-import.md` to describe the advisory exact-file reupload
   status and the preview token round trip.
3. Update `docs/database-schema.md` to document when `file_import` rows are
   created and how `file_import_id` is populated.
4. Update `docs/plans/duplicate-detection-enhancements.md` to replace the TODO
   with a link to this plan or mark the TODO implemented after code lands.
5. Update `README.md` if token signing configuration or preview token TTL is
   added.

## Test Plan

- Unit test preview extraction no longer exposes warnings.
- Unit test each extractor returns preview transactions directly.
- Controller test verifies preview response has no `warnings` field.
- Controller/OpenAPI test verifies `fileImport` schema.
- Controller/OpenAPI test verifies `previewImportToken` is documented and
  `contentHash` is not exposed.
- Service test verifies `fileImport.alreadyImported=false` when no prior record
  exists.
- Service test verifies `fileImport.alreadyImported=true` and previous import
  metadata when the same user previously imported the exact file bytes.
- Service test verifies another user's matching file hash does not trigger a
  warning.
- Token service tests cover valid token, bad signature, expiration, missing
  fields, and owner mismatch.
- Batch service test verifies no-token requests keep current behavior.
- Batch service/integration test verifies a token-backed successful batch
  import records `file_import`, links created transactions to it, and a later
  preview of the same bytes reports `FILE_ALREADY_IMPORTED`.
- Batch service test verifies token-backed batch import with all rows skipped
  as transaction duplicates does not create a `file_import` row.
- Batch service test verifies a previously imported exact file does not fail
  solely because of file import history; transaction duplicate handling remains
  the deciding behavior.

## Documentation Updates

- `docs/api/README.md`: remove `warnings`, document `fileImport` and
  `previewImportToken`.
- `docs/csv-import.md`: remove `warnings`, document exact-file reupload status
  and the preview token round trip.
- `docs/database-schema.md`: confirm how `file_import` is populated and queried.
- `docs/plans/duplicate-detection-enhancements.md`: replace the TODO with a
  link to this plan once implementation begins or completes.
- `README.md`: document token signing configuration if new configuration is
  introduced.
