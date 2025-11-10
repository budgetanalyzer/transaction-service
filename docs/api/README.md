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
Query params: page, size, sort, accountId, startDate, endDate, category
Response: Page<Transaction>
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
See: [@service-common/docs/error-handling.md](../../../service-common/docs/error-handling.md)

## Authentication & Authorization

**Status:** Not yet implemented

**Future:**
- OAuth 2.0 / JWT tokens
- Role-based access control (RBAC)
- User-scoped data access

**Current:** All endpoints publicly accessible (local dev only)

## Rate Limiting

**Status:** Not yet implemented

**Future:**
- Per-user rate limits
- Global rate limits
- Adaptive throttling

## Pagination

**Standard pagination for list endpoints:**

**Request:**
```
GET /api/v1/transactions?page=0&size=20&sort=transactionDate,desc
```

**Response:**
```json
{
  "content": [...],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {"sorted": true, "orders": [...]},
    "offset": 0
  },
  "totalPages": 5,
  "totalElements": 100,
  "last": false,
  "first": true,
  "numberOfElements": 20
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
- **Error Handling:** [@service-common/docs/error-handling.md](../../../service-common/docs/error-handling.md)
