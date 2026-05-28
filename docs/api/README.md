# Transaction Service - API Documentation

**Service:** transaction-service
**Base URL (Local):** http://localhost:8082
**Gateway URL:** http://localhost:8080/api/v1
**API Version:** v1

## Overview

This service provides RESTful APIs for financial transaction management, including CSV/PDF import, advanced filtering, and cross-user search. All endpoints follow RESTful conventions and return JSON responses.

## Quick Start

### Access API Documentation

**Swagger UI (Interactive):**
```bash
# Start service
./gradlew bootRun

# Open browser
open http://localhost:8082/swagger-ui.html
```

**OpenAPI Spec:**
```bash
# View spec
curl http://localhost:8082/v3/api-docs

# Download spec
curl http://localhost:8082/v3/api-docs > openapi.json
```

### Test Endpoints

```bash
# Health check
curl http://localhost:8082/actuator/health

# List transactions (requires auth headers)
curl -H "X-User-Id: usr_test123" -H "X-Permissions: transactions:read" \
  http://localhost:8082/v1/transactions
```

## API Endpoints

### Transactions

**List Transactions**
```
GET /v1/transactions
Response: List<TransactionResponse>
Permission: transactions:read
Notes: Returns only the requesting user's active (non-deleted) transactions.
```

**Count User Transactions**
```
GET /v1/transactions/count
Query params: id, accountId, bankName, dateFrom, dateTo, currencyIsoCode, minAmount, maxAmount, type, description, createdAfter, createdBefore, updatedAfter, updatedBefore
Response: long
Permission: transactions:read
Notes: Always scoped to the requesting user's active transactions.
```

**Get Transaction**
```
GET /v1/transactions/{id}
Response: TransactionResponse
Permission: transactions:read
```

**Update Transaction**
```
PATCH /v1/transactions/{id}
Body: TransactionUpdateRequest (description, accountId)
Response: TransactionResponse
Permission: transactions:write
Notes: Only description and accountId are mutable. All other fields are immutable.
```

**Delete Transaction**
```
DELETE /v1/transactions/{id}
Response: 204 No Content
Permission: transactions:delete
Notes: Soft-deletes the transaction (sets deleted=true).
```

**Bulk Delete Transactions**
```
POST /v1/transactions/bulk-delete
Body: { "ids": [1, 2, 3] }
Response: BulkDeleteResponse
Permission: transactions:delete
Notes: Soft-deletes multiple transactions. Returns deletedCount and notFoundIds.
```

**Preview Transactions (File Import)**
```
POST /v1/transactions/preview
Content-Type: multipart/form-data
Params: statementFormatId (required), accountId (optional), file (required)
Response: PreviewResponse
Permission: transactions:read
Notes: Parses a CSV or PDF file and returns extracted transactions for review. No data is persisted. Use GET /v1/statement-formats to list available statement format IDs. The multipart file part must include a non-blank filename. Uploads are limited by TRANSACTION_IMPORT_MAX_FILE_SIZE and TRANSACTION_IMPORT_MAX_REQUEST_SIZE, both defaulting to 25MB. Import duplicate and file reupload behavior is documented in Transaction Duplicate Detection.
```

**Batch Import Transactions**
```
POST /v1/transactions/batch
Body: BatchImportRequest (required previewImportToken, list of BatchImportTransactionRequest objects, optional allowDuplicate per row)
Response: BatchImportResponse (200 OK)
Permission: transactions:write
Notes: Imports reviewed transactions from the preview endpoint. The previewImportToken is required and verified before batch import processing starts. The request validates all rows upfront and persists accepted rows transactionally. Import duplicate and file import recording behavior is documented in Transaction Duplicate Detection.
```

See [Transaction Duplicate Detection](../duplicate-detection.md) for the
authoritative duplicate matching rules, file reupload tracking behavior,
`previewImportToken` semantics, and related error codes.

### Cross-User Transaction Search

**Search Transactions Across Users**
```
GET /v1/transactions/search
Query params: page, size, sort, ownerId, id, accountId, bankName, dateFrom, dateTo, currencyIsoCode, minAmount, maxAmount, type, description, createdAfter, createdBefore, updatedAfter, updatedBefore
Response: PagedResponse<TransactionResponse>
Permission: transactions:read:any
Notes: Default sort is date,desc then id,desc. Default page size is 50, maximum is 100. Supported sort fields: id, ownerId, accountId, bankName, date, currencyIsoCode, amount, type, description, createdAt, updatedAt. Unsupported sort fields return 400.
Contract: content contains TransactionResponse items (ownerId is a first-class field on every item). metadata contains page, size, numberOfElements, totalElements, totalPages, first, last.
```

**Count Transactions Across Users**
```
GET /v1/transactions/search/count
Query params: ownerId, id, accountId, bankName, dateFrom, dateTo, currencyIsoCode, minAmount, maxAmount, type, description, createdAfter, createdBefore, updatedAfter, updatedBefore
Response: long
Permission: transactions:read:any
Notes: Cross-user count endpoint. Does not require transactions:read.
```

Transaction text filtering uses `description`, which matches transaction
descriptions only. Text filters are case-insensitive, split multi-word input
into OR terms, and escape SQL LIKE wildcards.

### Saved Views

**Create Saved View**
```
POST /v1/views
Body: CreateSavedViewRequest
Response: SavedViewResponse (201 Created)
Permission: views:write
```

**List Saved Views**
```
GET /v1/views
Response: List<SavedViewResponse>
Permission: views:read
Notes: Returns only the requesting user's views.
```

**Get Saved View**
```
GET /v1/views/{id}
Response: SavedViewResponse
Permission: views:read
```

**Update Saved View**
```
PUT /v1/views/{id}
Body: UpdateSavedViewRequest
Response: SavedViewResponse
Permission: views:write
```

**Delete Saved View**
```
DELETE /v1/views/{id}
Response: 204 No Content
Permission: views:delete
```

**Get View Transactions**
```
GET /v1/views/{id}/transactions
Response: ViewMembershipResponse
Permission: views:read
Notes: Returns transactions matching the view's criteria, plus pinned/excluded overrides.
```

**Saved View Criteria**

See [Saved Views](../saved-views.md) for criteria fields, `openEnded`
behavior, pinned/excluded membership rules, and the current criteria JSON
contract.

**Pin Transaction to View**
```
POST /v1/views/{id}/pin/{txnId}
Response: SavedViewResponse
Permission: views:write
```

**Bulk Pin Transactions to View**
```
POST /v1/views/{id}/pin
Body: { "ids": [1, 2, 3] }
Response: BulkViewTransactionResponse
Permission: views:write
Notes: Returns updatedCount and notFoundIds. updatedCount counts unique valid
IDs, so duplicate valid IDs are applied once and counted once. notFoundIds
includes IDs that are missing, soft-deleted, or owned by another user. Returns
200 for full and partial success, 400 for null/empty ids, and 404 only when the
saved view is missing.
Response shape: { "updatedCount": 2, "notFoundIds": [99] }
```

**Unpin Transaction from View**
```
DELETE /v1/views/{id}/pin/{txnId}
Response: SavedViewResponse
Permission: views:write
```

**Exclude Transaction from View**
```
POST /v1/views/{id}/exclude/{txnId}
Response: SavedViewResponse
Permission: views:write
```

**Bulk Exclude Transactions from View**
```
POST /v1/views/{id}/exclude
Body: { "ids": [1, 2, 3] }
Response: BulkViewTransactionResponse
Permission: views:write
Notes: Returns updatedCount and notFoundIds. updatedCount counts unique valid
IDs, so duplicate valid IDs are applied once and counted once. notFoundIds
includes IDs that are missing, soft-deleted, or owned by another user. Returns
200 for full and partial success, 400 for null/empty ids, and 404 only when the
saved view is missing.
Response shape: { "updatedCount": 2, "notFoundIds": [99] }
```

**Remove Exclusion from View**
```
DELETE /v1/views/{id}/exclude/{txnId}
Response: SavedViewResponse
Permission: views:write
```

### Statement Formats

**List Statement Formats**
```
GET /v1/statement-formats
Response: List<StatementFormatResponse>
Permission: statementformats:read or statementformats:read:any
```

Seeded import formats include:
- Bangkok Bank CSV
- Bangkok Bank statement PDF
- Capital One credit monthly statement
- Capital One credit yearly statement
- Capital One bank monthly statement

The response includes each format's `id`. Use that ID for preview, get, and
update requests.

**Get Statement Format**
```
GET /v1/statement-formats/{id}
Response: StatementFormatResponse
Permission: statementformats:read or statementformats:read:any
```

**Create Statement Format**
```
POST /v1/statement-formats
Body: CreateStatementFormatRequest
Response: StatementFormatResponse (201 Created)
Permission: statementformats:write or statementformats:write:any
```

**Update Statement Format**
```
PUT /v1/statement-formats/{id}
Body: UpdateStatementFormatRequest
Response: StatementFormatResponse
Permission: statementformats:write or statementformats:write:any
```

## Request/Response Examples

### TransactionUpdateRequest

```json
{
  "description": "Whole Foods - groceries for dinner party",
  "accountId": "checking-12345"
}
```

### TransactionResponse

```json
{
  "id": 101,
  "ownerId": "usr_test123",
  "accountId": "checking-12345",
  "bankName": "Capital One",
  "date": "2025-11-10",
  "currencyIsoCode": "USD",
  "amount": 75.50,
  "type": "DEBIT",
  "description": "Restaurant dinner",
  "createdAt": "2025-11-10T18:30:00Z",
  "updatedAt": "2025-11-10T18:30:00Z"
}
```

### Cross-User Transaction Search Response

```json
{
  "content": [
    {
      "id": 101,
      "ownerId": "usr_test123",
      "accountId": "checking-12345",
      "bankName": "Capital One",
      "date": "2025-11-10",
      "currencyIsoCode": "USD",
      "amount": 75.50,
      "type": "DEBIT",
      "description": "Restaurant dinner",
      "createdAt": "2025-11-10T18:30:00Z",
      "updatedAt": "2025-11-10T18:30:00Z"
    }
  ],
  "metadata": {
    "page": 0,
    "size": 50,
    "numberOfElements": 1,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

### PreviewResponse

Fields:
- `sourceFile` - Original uploaded filename.
- `statementFormatId` - Statement format ID used for parsing.
- `previewImportToken` - Opaque encrypted source-file token required by `/batch`.
- `fileImport` - Exact-file reupload status for the authenticated user.
- `transactions` - Editable preview rows with advisory duplicate metadata.

```json
{
  "sourceFile": "statement.csv",
  "statementFormatId": 123,
  "previewImportToken": "v2.dGVzdGl2MTIzNDU.Kc4WwTqfh1sFD8pxVq7Hxg",
  "fileImport": {
    "alreadyImported": true,
    "warningCode": "FILE_ALREADY_IMPORTED",
    "previousImport": {
      "originalFilename": "statement.csv",
      "importedAt": "2026-05-01T12:34:56Z",
      "statementFormatId": 123,
      "accountId": "checking-12345",
      "transactionCount": 42
    }
  },
  "transactions": [
    {
      "date": "2026-04-28",
      "description": "COFFEE SHOP",
      "amount": 150.00,
      "type": "DEBIT",
      "bankName": "Bangkok Bank",
      "currencyIsoCode": "THB",
      "accountId": "checking-12345",
      "duplicate": true,
      "duplicateReason": "EXISTING_TRANSACTION"
    },
    {
      "date": "2026-04-29",
      "description": "SALARY TRANSFER",
      "amount": 5000.00,
      "type": "CREDIT",
      "bankName": "Bangkok Bank",
      "currencyIsoCode": "THB",
      "accountId": "checking-12345",
      "duplicate": false
    }
  ]
}
```

`duplicateReason` is `EXISTING_TRANSACTION` for active persisted matches and
`IN_BATCH` for matches against earlier rows in the same preview response. It is
omitted when `duplicate=false`. `fileImport` is file-level metadata, separate
from per-row transaction duplicate detection, and `previewImportToken` is the
opaque source-file token required by `/batch`. See
[Transaction Duplicate Detection](../duplicate-detection.md) for details.

### BatchImportRequest

Fields:
- `previewImportToken` - Required opaque token returned by the preview endpoint.
- `transactions` - Reviewed transaction rows to import.
- `allowDuplicate` - Optional per-row override, defaulting to `false`.

```json
{
  "previewImportToken": "v2.dGVzdGl2MTIzNDU.Kc4WwTqfh1sFD8pxVq7Hxg",
  "transactions": [
    {
      "date": "2026-04-28",
      "description": "COFFEE SHOP",
      "amount": 150.00,
      "type": "DEBIT",
      "bankName": "Bangkok Bank",
      "currencyIsoCode": "THB",
      "accountId": "checking-12345",
      "allowDuplicate": true
    }
  ]
}
```

Omit `allowDuplicate` or set it to `false` to skip rows that match duplicate
detection. Set it to `true` only for rows that should be intentionally imported
despite matching an existing transaction or an earlier row in the same batch.
`previewImportToken` is required and must be valid, unexpired, and owned by the
authenticated user. See
[Transaction Duplicate Detection](../duplicate-detection.md) for matching rules,
token verification order, and empty-import behavior.

### BatchImportResponse

```json
{
  "created": 1,
  "duplicatesSkipped": 0,
  "duplicatesImported": 1,
  "transactions": [
    {
      "id": 102,
      "ownerId": "usr_test123",
      "accountId": "checking-12345",
      "bankName": "Capital One",
      "date": "2026-04-28",
      "currencyIsoCode": "USD",
      "amount": 55.12,
      "type": "DEBIT",
      "description": "TAQUERIA DEL SOL #3",
      "createdAt": "2026-04-28T18:30:00Z",
      "updatedAt": "2026-04-28T18:30:00Z"
    }
  ]
}
```

### Error Responses

**Application error:**
```json
{
  "type": "APPLICATION_ERROR",
  "message": "Format not supported: fake-bank",
  "code": "FORMAT_NOT_SUPPORTED"
}
```

**Preview upload error:**
```json
{
  "type": "APPLICATION_ERROR",
  "message": "Uploaded file must include an original filename.",
  "code": "MISSING_ORIGINAL_FILENAME"
}
```

**Validation error:**
```json
{
  "type": "VALIDATION_ERROR",
  "message": "Validation failed for 2 field(s)",
  "fieldErrors": [
    { "field": "transactions[0].amount", "message": "must not be null" },
    { "field": "transactions[1].date", "message": "must not be null" }
  ]
}
```

## Error Handling

**Standard HTTP Status Codes:**
- `200 OK` - Successful GET/PATCH/bulk/batch import operations
- `201 Created` - Successful create operations
- `204 No Content` - Successful DELETE
- `400 Bad Request` - Validation error or invalid request
- `404 Not Found` - Resource not found
- `422 Unprocessable Entity` - Business rule violation (e.g., unsupported format)
- `500 Internal Server Error` - Server error

**Error Response Format:**
See: [@service-common/docs/error-handling.md](https://github.com/budgetanalyzer/service-common/blob/main/docs/error-handling.md)

## Authentication & Authorization

This service uses trusted claims-header-based security from `service-common`.

- Requests are authenticated from `X-User-Id`, `X-Permissions`, and `X-Roles` headers injected by
  the ingress auth path.
- `GET /v1/transactions` and `GET /v1/transactions/count` require
  `X-Permissions: transactions:read` and are always scoped to the requesting user.
- Write endpoints require `transactions:write`. Delete endpoints require `transactions:delete`.
- Saved view endpoints require `views:read`, `views:write`, or `views:delete` respectively.
- `POST /v1/views/{id}/pin` and `POST /v1/views/{id}/exclude` are owner-scoped bulk operations:
  they return `200` with `updatedCount` plus `notFoundIds` for IDs that are missing, deleted, or
  not owned by the caller; they return `404` only when the saved view is missing.
- `GET /v1/transactions/search` and `GET /v1/transactions/search/count` require
  `transactions:read:any` in `X-Permissions`. They do not require `transactions:read`.
- The `:any` variants of the per-resource permissions
  (`transactions:read:any`, `transactions:write:any`, `transactions:delete:any`)
  relax the ownership check on `GET`, `PATCH`, `DELETE /v1/transactions/{id}` and
  `POST /v1/transactions/bulk-delete`. The unscoped `transactions:read`,
  `transactions:write`, or `transactions:delete` permission is still required to enter the
  controller method; `:any` only allows the caller to act on transactions owned by other
  users. The `ADMIN` role bundles all three `:any` permissions in the current
  `permission-service` seed data. `views:*` intentionally has no `:any` variants yet.
- Statement format endpoints accept `statementformats:read` or
  `statementformats:read:any` for reads, and `statementformats:write` or
  `statementformats:write:any` for writes. Users can manage their own
  user-scoped formats. Creating or updating system formats requires the `:any`
  write variant.
- Disable a statement format through `PUT /v1/statement-formats/{id}` with
  `{"enabled": false}`.
- OpenAPI docs and health endpoints remain public.

Example local cross-user search request:

```bash
curl \
  -H "X-User-Id: usr_admin456" \
  -H "X-Permissions: transactions:read,transactions:read:any" \
  http://localhost:8082/v1/transactions/search
```

## Rate Limiting

**Status:** Not yet implemented

**Future:**
- Per-user rate limits
- Global rate limits
- Adaptive throttling

## Pagination

`GET /v1/transactions` remains an unpaged user-scoped list endpoint. The stable paged response
contract applies to cross-user search:

**Request:**
```
GET /v1/transactions/search?page=0&size=20&sort=date,desc&sort=id,desc
```

**Response:**
```json
{
  "content": [
    {
      "id": 101,
      "ownerId": "usr_test123",
      "accountId": "checking-12345",
      "bankName": "Capital One",
      "date": "2025-11-10",
      "currencyIsoCode": "USD",
      "amount": 75.50,
      "type": "DEBIT",
      "description": "Restaurant dinner",
      "createdAt": "2025-11-10T18:30:00Z",
      "updatedAt": "2025-11-10T18:30:00Z"
    }
  ],
  "metadata": {
    "page": 0,
    "size": 20,
    "numberOfElements": 1,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

## Validation Rules

### Batch Import (PreviewTransaction)

- `date` - Required
- `description` - Required, non-blank
- `amount` - Required
- `type` - Required, DEBIT or CREDIT
- `bankName` - Required, non-blank
- `currencyIsoCode` - Required, non-blank
- `accountId` - Optional
- `category` - Optional
- `allowDuplicate` - Optional, defaults to false. When true, imports the row
  even if duplicate detection matches it.

### Transaction Update (TransactionUpdateRequest)

- `description` - Optional, max 500 characters
- `accountId` - Optional, max 100 characters

## Discovery Commands

```bash
# Find all controllers
grep -r "@RestController" src/main/java

# Find all endpoints
grep -r "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" src/main/java

# View OpenAPI configuration
cat src/main/java/*/config/OpenApiConfig.java
```

## References

- **Swagger UI:** http://localhost:8082/swagger-ui.html (when service running)
- **OpenAPI Spec:** http://localhost:8082/v3/api-docs
- **Domain Model:** [../domain-model.md](../domain-model.md)
- **Error Handling:** [@service-common/docs/error-handling.md](https://github.com/budgetanalyzer/service-common/blob/main/docs/error-handling.md)
