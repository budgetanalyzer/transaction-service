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
Params: format (required), accountId (optional), file (required)
Response: PreviewResponse
Permission: transactions:read
Notes: Parses a CSV or PDF file and returns extracted transactions for review. No data is persisted. Use GET /v1/statement-formats to list available format keys.
```

**Batch Import Transactions**
```
POST /v1/transactions/batch
Body: BatchImportRequest (list of PreviewTransaction objects)
Response: BatchImportResponse (201 Created)
Permission: transactions:write
Notes: Imports transactions from the preview endpoint after user edits. Validates all upfront; rejects entire batch on failure. Duplicates (date + amount + description) are skipped.
```

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

### Saved Views

**Create Saved View**
```
POST /v1/views
Body: CreateSavedViewRequest
Response: SavedViewResponse (201 Created)
Permission: transactions:write
```

**List Saved Views**
```
GET /v1/views
Response: List<SavedViewResponse>
Permission: transactions:read
Notes: Returns only the requesting user's views.
```

**Get Saved View**
```
GET /v1/views/{id}
Response: SavedViewResponse
Permission: transactions:read
```

**Update Saved View**
```
PUT /v1/views/{id}
Body: UpdateSavedViewRequest
Response: SavedViewResponse
Permission: transactions:write
```

**Delete Saved View**
```
DELETE /v1/views/{id}
Response: 204 No Content
Permission: transactions:write
```

**Get View Transactions**
```
GET /v1/views/{id}/transactions
Response: List<ViewMembershipResponse>
Permission: transactions:read
Notes: Returns transactions matching the view's criteria, plus pinned/excluded overrides.
```

**Pin Transaction to View**
```
POST /v1/views/{id}/pin/{txnId}
Response: SavedViewResponse
Permission: transactions:write
```

**Unpin Transaction from View**
```
DELETE /v1/views/{id}/pin/{txnId}
Response: SavedViewResponse
Permission: transactions:write
```

**Exclude Transaction from View**
```
POST /v1/views/{id}/exclude/{txnId}
Response: SavedViewResponse
Permission: transactions:write
```

**Remove Exclusion from View**
```
DELETE /v1/views/{id}/exclude/{txnId}
Response: SavedViewResponse
Permission: transactions:write
```

### Statement Formats

**List Statement Formats**
```
GET /v1/statement-formats
Response: List<StatementFormatResponse>
Permission: statementformats:read
```

**Get Statement Format**
```
GET /v1/statement-formats/{formatKey}
Response: StatementFormatResponse
Permission: statementformats:read
```

**Create Statement Format**
```
POST /v1/statement-formats
Body: CreateStatementFormatRequest
Response: StatementFormatResponse (201 Created)
Permission: statementformats:write
```

**Update Statement Format**
```
PUT /v1/statement-formats/{formatKey}
Body: UpdateStatementFormatRequest
Response: StatementFormatResponse
Permission: statementformats:write
```

**Delete Statement Format**
```
DELETE /v1/statement-formats/{formatKey}
Response: 204 No Content
Permission: statementformats:delete
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

### Error Responses

**Application error:**
```json
{
  "type": "APPLICATION_ERROR",
  "message": "Format not supported: fake-bank",
  "code": "FORMAT_NOT_SUPPORTED"
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
- `200 OK` - Successful GET/PATCH/bulk operations
- `201 Created` - Successful POST (batch import, create)
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
- `GET /v1/transactions/search` and `GET /v1/transactions/search/count` require
  `transactions:read:any` in `X-Permissions`. They do not require `transactions:read`.
- The `:any` variants of the per-resource permissions
  (`transactions:read:any`, `transactions:write:any`, `transactions:delete:any`)
  relax the ownership check on `GET`, `PATCH`, `DELETE /v1/transactions/{id}` and
  `POST /v1/transactions/bulk-delete`. The unscoped `transactions:read`,
  `transactions:write`, or `transactions:delete` permission is still required to enter the
  controller method; `:any` only allows the caller to act on transactions owned by other
  users. The `ADMIN` role bundles all three `:any` permissions via `permission-service`
  migration `V5__add_cross_user_transaction_permissions.sql`.
- Statement format endpoints require `statementformats:read`, `statementformats:write`, or
  `statementformats:delete` respectively.
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
