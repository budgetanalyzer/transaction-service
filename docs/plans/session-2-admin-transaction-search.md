# Session 2: Admin Transaction Search Endpoint

## Context

The admin UI needs to browse transactions across all users with pagination, filtering, and sorting.
The existing `GET /v1/transactions` endpoint won't work because it scopes results to the calling
user, returns no owner info, has no pagination, and doesn't wire filters to the GET endpoint.

This session implements `GET /v1/admin/transactions` as the first consumer of the `PagedResponse<T>`
contract built in Session 1, and refactors the service layer to cleanly separate user and admin
search flows.

**Parent plan**: [transaction-search-pagination-plan.md](../../service-common/docs/plans/transaction-search-pagination-plan.md)

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Authorization | `@PreAuthorize("hasRole('ADMIN')")` at controller class level AND service method level | Defense in depth — admin search is protected regardless of call path. |
| Service layer separation | `getTransactions(userId)` for users, `search(filter, pageable)` for admins | Two distinct operations: "get my transactions" vs "search all transactions." No `isAdmin` boolean in `getTransactions`. |
| Response DTO | New `AdminTransactionResponse` record | Adds `ownerId` field. Keeps non-admin `TransactionResponse` untouched. |
| Filter DTO | Add `ownerId` to existing `TransactionFilter` | Avoids duplicating 14 fields. Field is unused by non-admin endpoints. |
| Pagination | Spring `Pageable` + `PagedResponse<AdminTransactionResponse>` | Reuses Session 1 contract. |
| Default sort | `date,DESC` then `id,DESC` | Deterministic and business-relevant per pagination plan. |
| Existing endpoints | `GET /v1/transactions` simplified | Drops `isAdmin` logic — always scoped to the requesting user. |

## Deliverables

### Workflow 1. Add `ownerId` to `TransactionFilter` ✅

**File**: `src/main/java/org/budgetanalyzer/transaction/api/request/TransactionFilter.java`

- Add `ownerId` field (String, nullable) as the second parameter (after `id`, before `accountId`).
- Add `@Schema` annotation matching existing field style.
- Update `empty()` factory to include the new `null` parameter.

### Workflow 2. Add `ownerId` spec to `TransactionSpecifications` ✅

**File**: `src/main/java/org/budgetanalyzer/transaction/repository/spec/TransactionSpecifications.java`

- In `withFilter()`, add an exact-match predicate for `ownerId` when non-null.
- Place it after the `id` predicate block, before `accountId`.
- This is a simple `cb.equal(root.get("ownerId"), filter.ownerId())` — no LIKE, no case folding.

### Workflow 3. Create `AdminTransactionResponse` ✅

**File**: `src/main/java/org/budgetanalyzer/transaction/api/response/AdminTransactionResponse.java`

```java
public record AdminTransactionResponse(
    Long id,
    String ownerId,
    String accountId,
    String bankName,
    LocalDate date,
    String currencyIsoCode,
    BigDecimal amount,
    TransactionType type,
    String description,
    Instant createdAt,
    Instant updatedAt) {

  public static AdminTransactionResponse from(Transaction transaction) { ... }
}
```

- Include `@Schema` annotations matching `TransactionResponse` style.
- `ownerId` field with `@Schema(description = "ID of the user who owns this transaction")`.

### Workflow 4. Refactor `TransactionService` search methods ✅

**File**: `src/main/java/org/budgetanalyzer/transaction/service/TransactionService.java`

**Replace** existing `search(TransactionFilter filter, String userId, boolean isAdmin)` with:

```java
public List<Transaction> getTransactions(String userId) {
    return transactionRepository.findAllActive(TransactionSpecifications.byOwner(userId));
}
```

Always scoped to the requesting user. No `isAdmin` bypass — admins use the search endpoint.

**Rename** existing `searchPaged` → `search`, add `@PreAuthorize`:

```java
@PreAuthorize("hasRole('ADMIN')")
public Page<Transaction> search(TransactionFilter filter, Pageable pageable) {
    var spec = TransactionSpecifications.withFilter(filter);
    return transactionRepository.findAllActive(spec, pageable);
}
```

Protected at the service level — Spring Security rejects non-admin callers regardless of how the
method is invoked. The `ownerId` in `TransactionFilter` handles optional user-narrowing via the spec.

### Workflow 5. Create `AdminTransactionController`

**File**: `src/main/java/org/budgetanalyzer/transaction/api/AdminTransactionController.java`

```java
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Transactions", description = "Admin transaction search and management")
@RestController
@RequestMapping(path = "/v1/admin/transactions")
public class AdminTransactionController {

    private final TransactionService transactionService;

    // Constructor injection

    @Operation(summary = "Search transactions across all users")
    @GetMapping(produces = "application/json")
    public PagedResponse<AdminTransactionResponse> searchTransactions(
            @Valid TransactionFilter filter,
            @PageableDefault(size = 50, sort = {"date", "id"}, direction = Sort.Direction.DESC)
            Pageable pageable) {
        var page = transactionService.search(filter, pageable);
        return PagedResponse.from(page, AdminTransactionResponse::from);
    }
}
```

- Class-level `@PreAuthorize("hasRole('ADMIN')")` gates all methods.
- `@PageableDefault` sets default sort to `date,DESC` + `id,DESC`.
- Returns `PagedResponse<AdminTransactionResponse>` using the `from(Page, mapper)` factory.
- Single endpoint for now — add admin CRUD later if needed.

### Workflow 6. Tests

#### Workflow 6a. `AdminTransactionControllerAuthorizationTest`

**File**: `src/test/java/org/budgetanalyzer/transaction/api/AdminTransactionControllerAuthorizationTest.java`

`@WebMvcTest(AdminTransactionController.class)` with `ClaimsHeaderTestBuilder`:

| Test | Auth setup | Expected |
|------|-----------|----------|
| No authentication | (none) | 401 |
| Regular user with `transactions:read` | `.user().withPermissions("transactions:read")` | 403 |
| Admin user | `.admin()` | 200 |
| User with ADMIN role but no permissions | `.user().withRoles("ADMIN").withPermissions()` | 200 |

#### Workflow 6b. `AdminTransactionControllerTest`

**File**: `src/test/java/org/budgetanalyzer/transaction/api/AdminTransactionControllerTest.java`

`@WebMvcTest` with mocked `TransactionService`:

- Pagination params binding (`page`, `size`, `sort` query params).
- Default sort behavior (when no sort param supplied).
- JSON response shape matches `PagedResponse<AdminTransactionResponse>` contract.
- Filter params binding (existing filter fields + `ownerId`).
- `ownerId` present in each response item.
- Empty page response.

#### Workflow 6c. `TransactionServiceTest` changes ✅

**File**: `src/test/java/org/budgetanalyzer/transaction/service/TransactionServiceTest.java`

Refactor existing tests:
- Remove `search_admin_noOwnerFilter` (no admin path in `getTransactions`).
- Rename `search_nonAdmin_filtersByOwner` → `getTransactions_filtersByOwner` — call `getTransactions(userId)`, verify owner spec applied.

Add new tests:
- `search` delegates to `findAllActive(spec, pageable)`.
- Verify spec includes filter predicates.
- Verify pageable is passed through.

#### Workflow 6e. `TransactionControllerTest` / `AuthorizationTest` changes ✅

**Files**:
- `src/test/java/org/budgetanalyzer/transaction/api/TransactionControllerTest.java`
- `src/test/java/org/budgetanalyzer/transaction/api/TransactionControllerAuthorizationTest.java`

- Update mocks from `transactionService.search(any(), anyString(), anyBoolean())` to `transactionService.getTransactions(anyString())`.

#### Workflow 6d. `TransactionSpecifications` additions ✅

**File**: `src/test/java/org/budgetanalyzer/transaction/repository/spec/TransactionSpecificationsIntegrationTest.java`

- `ownerId` filter produces correct predicate (exact match, case-sensitive).
- `ownerId` combined with other filters works correctly.

## Files Modified (summary)

| File | Change |
|------|--------|
| `api/request/TransactionFilter.java` | Add `ownerId` field |
| `repository/spec/TransactionSpecifications.java` | Add `ownerId` predicate |
| `api/response/AdminTransactionResponse.java` | **New** — admin response with `ownerId` |
| `api/AdminTransactionController.java` | **New** — admin search endpoint |
| `service/TransactionService.java` | Replace `search(filter,userId,isAdmin)` with `getTransactions(userId)`, rename `searchPaged` → `search` with `@PreAuthorize` |
| `api/TransactionController.java` | Update `getTransactions()` to call `getTransactions(userId)` — drop `isAdmin` |
| `test/.../AdminTransactionControllerAuthorizationTest.java` | **New** |
| `test/.../AdminTransactionControllerTest.java` | **New** |
| `test/.../TransactionControllerTest.java` | Update mock to `getTransactions(anyString())` |
| `test/.../TransactionControllerAuthorizationTest.java` | Update mock to `getTransactions(anyString())` |
| `test/.../TransactionServiceTest.java` | Refactor `search_*` tests → `getTransactions_*`, add `search` tests |
| `test/.../TransactionSpecificationsIntegrationTest.java` | Add `ownerId` filter tests |

## Verification

```bash
# Format and build
./gradlew clean spotlessApply build

# Run only admin controller tests
./gradlew test --tests "*AdminTransaction*"

# Run full test suite
./gradlew test

# Manual smoke test (with service running)
# Admin search with pagination:
curl -H "X-User-Id: usr_admin456" -H "X-Roles: ADMIN" -H "X-Permissions: transactions:read" \
  "http://localhost:8082/v1/admin/transactions?page=0&size=20&sort=date,desc"

# Admin search with ownerId filter:
curl -H "X-User-Id: usr_admin456" -H "X-Roles: ADMIN" -H "X-Permissions: transactions:read" \
  "http://localhost:8082/v1/admin/transactions?ownerId=usr_test123&page=0&size=20"

# Non-admin user should get 403:
curl -H "X-User-Id: usr_test123" -H "X-Roles: USER" -H "X-Permissions: transactions:read" \
  "http://localhost:8082/v1/admin/transactions"
```

## Implementation Order

1. `TransactionService` — refactor: `search` → `getTransactions(userId)`, rename `searchPaged` → `search` with `@PreAuthorize`
2. `TransactionController` — update `getTransactions()` to drop `isAdmin`
3. Existing tests — update mocks and rename test methods
4. `TransactionFilter` — add `ownerId` field
5. `TransactionSpecifications` — add `ownerId` predicate
6. `AdminTransactionResponse` — new response DTO
7. `AdminTransactionController` — new controller
8. New tests — admin controller + authorization tests
9. `spotlessApply` + `build`
