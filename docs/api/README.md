# Transaction Service - API Documentation

**Service:** transaction-service
**Base URL (Local):** http://localhost:8082
**Gateway URL:** http://localhost:8080/api/v1
**API Version:** v1

## Overview

This service provides RESTful APIs for transaction and budget management. All endpoints follow RESTful conventions and return JSON responses.

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

# List transactions
curl http://localhost:8080/api/v1/transactions

# Create transaction
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "123e4567-e89b-12d3-a456-426614174000",
    "amount": 50.00,
    "currency": "USD",
    "transactionDate": "2025-11-10",
    "description": "Grocery shopping",
    "type": "DEBIT"
  }'
```

## API Endpoints

### Transactions

**List Transactions**
```
GET /api/v1/transactions
Response: List<Transaction>
Notes: Returns only the requesting user's active transactions.
```

**Count User Transactions**
```
GET /api/v1/transactions/count
Query params: id, accountId, bankName, dateFrom, dateTo, currencyIsoCode, minAmount, maxAmount, type, description, createdAfter, createdBefore, updatedAfter, updatedBefore
Response: long
Notes: Always scoped to the requesting user's active transactions.
```

**Admin Search Transactions**
```
GET /api/v1/admin/transactions
Query params: page, size, sort, ownerId, id, accountId, bankName, dateFrom, dateTo, currencyIsoCode, minAmount, maxAmount, type, description, createdAfter, createdBefore, updatedAfter, updatedBefore
Response: PagedResponse<AdminTransactionResponse>
Notes: Admin only. Requires the `ADMIN` role. Default sort is `date,desc` then `id,desc`. Maximum page size is `100`. Supported sort fields are `id`, `ownerId`, `accountId`, `bankName`, `date`, `currencyIsoCode`, `amount`, `type`, `description`, `createdAt`, and `updatedAt`. Unsupported sort fields return `400`.
Contract: `content` contains `AdminTransactionResponse` items. `metadata` contains `page`, `size`, `numberOfElements`, `totalElements`, `totalPages`, `first`, and `last`.
```

**Admin Count Transactions**
```
GET /api/v1/admin/transactions/count
Query params: ownerId, id, accountId, bankName, dateFrom, dateTo, currencyIsoCode, minAmount, maxAmount, type, description, createdAfter, createdBefore, updatedAfter, updatedBefore
Response: long
Notes: Admin only cross-user count endpoint. Requires the `ADMIN` role.
```

**Get Transaction**
```
GET /api/v1/transactions/{id}
Response: Transaction
```

**Create Transaction**
```
POST /api/v1/transactions
Body: TransactionRequest
Response: Transaction (201 Created)
```

**Update Transaction**
```
PUT /api/v1/transactions/{id}
Body: TransactionRequest
Response: Transaction
```

**Delete Transaction**
```
DELETE /api/v1/transactions/{id}
Response: 204 No Content
```

**Search Transactions**
```
POST /api/v1/transactions/search
Body: TransactionSearchCriteria
Response: Page<Transaction>
```

**Import CSV**
```
POST /api/v1/transactions/import
Body: multipart/form-data (files[], bankFormat)
Response: TransactionImportResponse
```

### Budgets

**List Budgets**
```
GET /api/v1/budgets
Query params: page, size, sort, category, startDate, endDate
Response: Page<Budget>
```

**Get Budget**
```
GET /api/v1/budgets/{id}
Response: Budget
```

**Create Budget**
```
POST /api/v1/budgets
Body: BudgetRequest
Response: Budget (201 Created)
```

**Update Budget**
```
PUT /api/v1/budgets/{id}
Body: BudgetRequest
Response: Budget
```

**Delete Budget**
```
DELETE /api/v1/budgets/{id}
Response: 204 No Content
```

### Categories

**List Categories**
```
GET /api/v1/categories
Query params: type (INCOME/EXPENSE)
Response: List<Category>
```

**Get Category**
```
GET /api/v1/categories/{id}
Response: Category
```

**Create Category**
```
POST /api/v1/categories
Body: CategoryRequest
Response: Category (201 Created)
```

## Request/Response Examples

### TransactionRequest

```json
{
  "accountId": "123e4567-e89b-12d3-a456-426614174000",
  "amount": 75.50,
  "currency": "USD",
  "transactionDate": "2025-11-10",
  "description": "Restaurant dinner",
  "category": "Food & Dining",
  "type": "DEBIT"
}
```

### Transaction Response

```json
{
  "id": "987e6543-e21b-12d3-a456-426614174000",
  "accountId": "123e4567-e89b-12d3-a456-426614174000",
  "amount": 75.50,
  "currency": "USD",
  "transactionDate": "2025-11-10",
  "description": "Restaurant dinner",
  "category": "Food & Dining",
  "type": "DEBIT",
  "createdAt": "2025-11-10T18:30:00Z",
  "updatedAt": "2025-11-10T18:30:00Z"
}
```

### Admin Transaction Search Response

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

### Error Response

```json
{
  "timestamp": "2025-11-10T18:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid transaction date: date cannot be in the future",
  "path": "/api/v1/transactions"
}
```

## Error Handling

**Standard HTTP Status Codes:**
- `200 OK` - Successful GET/PUT
- `201 Created` - Successful POST
- `204 No Content` - Successful DELETE
- `400 Bad Request` - Validation error
- `404 Not Found` - Resource not found
- `409 Conflict` - Business rule violation
- `500 Internal Server Error` - Server error

**Error Response Format:**
See: [@service-common/docs/error-handling.md](https://github.com/budgetanalyzer/service-common/blob/main/docs/error-handling.md)

## Authentication & Authorization

This service uses trusted claims-header-based security from `service-common`.

- Requests are authenticated from `X-User-Id`, `X-Permissions`, and `X-Roles` headers injected by
  the ingress auth path.
- `GET /api/v1/transactions` and `GET /api/v1/transactions/count` require
  `X-Permissions: transactions:read`.
- Write endpoints require `transactions:write`.
- `GET /api/v1/admin/transactions` and `GET /api/v1/admin/transactions/count` require the
  `ADMIN` role in `X-Roles`. They do not require `transactions:read`.
- OpenAPI docs and health endpoints remain public.

Example local admin request:

```bash
curl \
  -H "X-User-Id: usr_admin456" \
  -H "X-Roles: ADMIN" \
  http://localhost:8082/transaction-service/v1/admin/transactions
```

## Rate Limiting

**Status:** Not yet implemented

**Future:**
- Per-user rate limits
- Global rate limits
- Adaptive throttling

## Pagination

`GET /api/v1/transactions` remains an unpaged user-scoped list endpoint. The stable paged response
contract applies to admin search:

**Request:**
```
GET /api/v1/admin/transactions?page=0&size=20&sort=date,desc&sort=id,desc
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

### Transaction

- `accountId` - Required, valid UUID
- `amount` - Required, positive number
- `currency` - Required, valid ISO 4217 code
- `transactionDate` - Required, not in future
- `description` - Required, 1-500 characters
- `type` - Required, DEBIT or CREDIT

### Budget

- `name` - Required, 1-200 characters
- `amount` - Required, positive number
- `currency` - Required, valid ISO 4217 code
- `startDate` - Required
- `endDate` - Required, after startDate
- `category` - Optional, 1-100 characters

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
