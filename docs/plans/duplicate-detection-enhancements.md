# Duplicate Detection Enhancements

## Context

Transaction duplicate detection currently happens only during
`POST /v1/transactions/batch`. Preview extraction through
`POST /v1/transactions/preview` parses the uploaded file and returns parser
warnings, but it does not query existing transactions and does not mark rows as
duplicates.

The current batch duplicate key is:

```text
date | amount | description
```

That is too broad for this service because transactions are multi-account and
multi-currency. A same-day, same-amount, same-description transaction can be
valid on a different account, bank, currency, or transaction type.

## Goals

- Include `accountId`, `bankName`, `currencyIsoCode`, and `type` in duplicate
  detection.
- Treat nullable `accountId` consistently and safely.
- Add duplicate indicators to preview responses so the UI can surface them
  before import.
- Keep `/batch` as the authoritative duplicate enforcement point.
- Add a batch-import mechanism that allows selected duplicate rows to be
  imported intentionally.
- Preserve owner-scoped duplicate detection so different users can import the
  same transaction independently.

## Non-Goals

- Do not remove the final duplicate check from `/batch`.
- Do not make preview persistence-aware beyond duplicate lookup metadata.
- Do not introduce cross-user duplicate detection.
- Do not change PDF or CSV parsing rules except as needed to carry duplicate
  metadata through the response.

## Proposed Duplicate Key

Use the following owner-scoped duplicate fields:

```text
accountId | bankName | date | amount | type | currencyIsoCode | description
```

`ownerId` should remain a repository query scope rather than a value embedded in
the key returned to the service.

Null-safe `accountId` handling:

- `null` account IDs should compare equal to other `null` account IDs.
- `null` account IDs should not compare equal to non-null account IDs.
- Empty string account IDs should be treated the same as `null`.

Implementation detail:

- Centralize duplicate-key construction in one service/helper so preview and
  batch import cannot drift.
- Match Java key generation and SQL key generation exactly.
- Keep amount canonicalization at scale 2 to match the existing database column
  shape.
- Keep description matching exact. Do not normalize case, leading/trailing
  spaces, or internal whitespace beyond whatever the extractor/import request
  already supplies.
- Prefer explicit null markers or structured key construction over plain
  unescaped string concatenation where practical.

## Preview Contract Changes

Enhance preview responses with per-transaction duplicate metadata.

Proposed response shape:

```json
{
  "sourceFile": "statement.pdf",
  "detectedFormat": "capital-one-credit-monthly-statement",
  "transactions": [
    {
      "date": "2025-11-18",
      "description": "COFFEE SHOP",
      "amount": 9.97,
      "type": "DEBIT",
      "bankName": "Capital One",
      "currencyIsoCode": "USD",
      "accountId": "capital-one-credit",
      "duplicate": true,
      "duplicateReason": "EXISTING_TRANSACTION"
    }
  ],
  "warnings": []
}
```

Suggested duplicate reasons:

- `EXISTING_TRANSACTION`: matches an active transaction already in the database
  for the current user.
- `IN_BATCH`: duplicates an earlier transaction in the same preview payload.

Do not include matching transaction IDs in preview duplicate metadata.

Preview duplicate flags are advisory. `/batch` must re-check duplicates because
transactions can change after preview and before import.

Controller impact:

- `TransactionController.previewTransactions(...)` already has access to the
  current user via the security context.
- Pass `userId` into the preview service so it can perform owner-scoped
  duplicate lookup.
- Keep authorization as `transactions:read`.

Service impact:

- Update `TransactionImportService.previewFile(...)` to accept `userId`.
- After extraction, call duplicate detection with the extracted preview
  transactions and annotate each preview item.
- Consider a new service DTO such as `PreviewTransactionDuplicateStatus` or an
  enriched preview DTO rather than mixing HTTP response concerns into extractor
  DTOs.

## Batch Import Allow-Duplicate Contract

Add a per-transaction flag to the batch import request:

```json
{
  "transactions": [
    {
      "date": "2025-11-18",
      "description": "COFFEE SHOP",
      "amount": 9.97,
      "type": "DEBIT",
      "bankName": "Capital One",
      "currencyIsoCode": "USD",
      "accountId": "capital-one-credit",
      "allowDuplicate": true
    }
  ]
}
```

Default behavior:

- `allowDuplicate` defaults to `false`.
- When `false`, a duplicate is skipped as today.
- When `true`, that row bypasses duplicate skipping and is persisted.
- `allowDuplicate` is an unconditional per-row override. It applies to both
  duplicates that already exist in the database and duplicates within the same
  submitted batch.

Batch response:

- Keep `duplicatesSkipped` for rows skipped because duplicates were not allowed.
- Add `duplicatesImported` so the UI can confirm intentionally imported
  duplicates.

Suggested response shape:

```json
{
  "created": 1,
  "duplicatesSkipped": 0,
  "duplicatesImported": 1,
  "transactions": []
}
```

## Implementation Steps

1. Add a duplicate-key value object/helper. **Implemented.**
   - Include `accountId`, `bankName`, `date`, canonical `amount`, `type`,
     `currencyIsoCode`, and `description`.
   - Make null handling explicit and testable.

2. Update repository duplicate lookup. **Implemented.**
   - Replace the current `date|amount|description` lookup with the expanded
     key.
   - Ensure SQL uses the same account ID null behavior as Java.
   - Update `V11__add_duplicate_detection_index.sql` follow-up migration with a
     new index that matches the expanded query shape.

3. Update batch import. **Implemented.**
   - Extend `BatchImportTransactionRequest` and the service DTO with
     `allowDuplicate`.
   - Skip duplicates only when `allowDuplicate` is false.
   - Track skipped duplicates and intentionally imported duplicates separately.

4. Update preview import.
   - Pass current `userId` into `TransactionImportService.previewFile(...)`.
   - Run duplicate detection after extraction.
   - Mark each preview transaction with `duplicate` and `duplicateReason`.
   - Detect both database duplicates and duplicates within the preview payload.

5. Update API DTOs and OpenAPI schemas.
   - Add preview duplicate fields.
   - Add batch `allowDuplicate`.
   - Add any new batch response count fields.

6. Update documentation.
   - `docs/api/README.md`: preview response, batch request, batch response, and
     duplicate key behavior.
   - `docs/csv-import.md`: remove stale note that duplicate detection is not
     implemented and document preview duplicate indicators.
   - `docs/database-schema.md`: document the new duplicate-detection index.

## Test Plan

- Unit tests for duplicate-key construction.
  - Includes all duplicate fields.
  - `null` account ID equals `null`.
  - `null` account ID does not equal non-null.
  - Empty account ID equals `null`.
  - Amount scale normalization is stable.
  - Description matching stays exact.

- Repository integration tests.
  - Same expanded key matches for the same owner.
  - Different account ID does not match.
  - Different bank name does not match.
  - Different currency does not match.
  - Different type does not match.
  - Different owner does not match.
  - Deleted transactions do not match.

- Batch import service tests.
  - Existing duplicate is skipped by default.
  - Existing duplicate is imported when `allowDuplicate` is true.
  - Intra-batch duplicate is skipped by default.
  - Intra-batch duplicate is imported when allowed.
  - `duplicatesSkipped` and `duplicatesImported` are counted correctly.

- Preview service tests.
  - Existing database duplicates are flagged.
  - In-preview duplicates are flagged.
  - Non-duplicates are not flagged.
  - Preview remains advisory and batch still re-checks.

- Controller/API tests.
  - Preview response includes duplicate metadata.
  - Batch request accepts `allowDuplicate`.
  - Batch response returns updated duplicate counts.

## Resolved Decisions

- Empty `accountId` and `null` `accountId` are equivalent.
- `allowDuplicate` is unconditional and applies to database duplicates and
  in-batch duplicates.
- Preview duplicate metadata does not include matching transaction IDs.
- Batch responses include `duplicatesImported`.
- Description matching remains exact.

No open behavior questions remain before implementation.

## TODO

- Add file content hash comparison to the preview API. The preview flow should
  compute the uploaded file hash and check `file_import` for a prior import by
  the current user so the UI can warn about exact-file reuploads before batch
  import. Keep this separate from transaction-level duplicate detection:
  transaction duplicates remain advisory in preview and authoritative in
  `/batch`, while file hash comparison only identifies the same uploaded file
  bytes.
