# Bulk Saved-View Pin And Exclude Plan

## Goal

Add bulk pin and bulk exclude operations to saved views. The behavior should
match the transaction bulk delete pattern: process every requested ID, return a
success count plus IDs that could not be processed, and keep the operation
transactional.

## Phase 1: Contract And Guardrails

- Read `../service-common/docs/code-quality-standards.md` before Java edits.
- Read `../service-common/docs/testing-patterns.md` before adding tests.
- Use these API contracts:
  - `POST /v1/views/{id}/pin`
  - `POST /v1/views/{id}/exclude`
  - Request body: `{ "ids": [1, 2, 3] }`
  - Response body: count of successfully processed IDs plus `notFoundIds`.
- Match transaction bulk delete response semantics:
  - Return `200 OK` for full or partial success.
  - Return `400 Bad Request` for null or empty ID lists.
  - Return `404 Not Found` only when the saved view itself is missing.
  - Treat nonexistent, soft-deleted, and non-owned transactions as `notFoundIds`.
- Require `views:write` on both endpoints.
- Keep all saved-view membership owner-scoped. A user must not be able to persist
  another user's transaction ID into their saved view.

## Phase 2: API DTOs

- Add a request DTO under `src/main/java/org/budgetanalyzer/transaction/api/request/`.
  A likely name is `BulkViewTransactionRequest`.
- Model it after `BulkDeleteRequest`:
  - `@NotNull(message = "Transaction IDs list cannot be null")`
  - `@NotEmpty(message = "Transaction IDs list cannot be empty")`
  - `List<Long> ids`
- Add a response DTO under `src/main/java/org/budgetanalyzer/transaction/api/response/`.
  A likely name is `BulkViewTransactionResponse`.
- Prefer a generic response unless action-specific names are required by the API:
  - `int updatedCount`
  - `List<Long> notFoundIds`
- Add OpenAPI `@Schema` descriptions and examples consistent with
  `BulkDeleteResponse`.

## Phase 3: Domain And Service

- Add bulk helpers to `SavedView`:
  - `pinTransactions(Collection<Long> transactionIds)`
  - `excludeTransactions(Collection<Long> transactionIds)`
- Preserve existing single-ID semantics:
  - Pinning adds IDs to `pinnedIds` and removes them from `excludedIds`.
  - Excluding adds IDs to `excludedIds` and removes them from `pinnedIds`.
- Add service methods to `SavedViewService`:
  - `bulkPinTransactions(UUID viewId, String userId, List<Long> ids)`
  - `bulkExcludeTransactions(UUID viewId, String userId, List<Long> ids)`
- Return a service-layer result record similar to
  `TransactionService.BulkDeleteResult`.
- Implementation shape:
  - Load the view with `getView(viewId, userId)`.
  - Iterate all requested IDs.
  - For each ID, verify the transaction is active and owned by `userId`.
  - Add invalid IDs to `notFoundIds`.
  - Apply valid IDs to the relevant saved-view set.
  - Save the view once at the end.
- Keep the methods `@Transactional`. Unexpected failures should roll back the
  saved-view mutation.

## Phase 4: Controller Endpoints

- Add two handlers to `SavedViewController`:
  - `@PostMapping(path = "/{id}/pin", consumes = "application/json", produces = "application/json")`
  - `@PostMapping(path = "/{id}/exclude", consumes = "application/json", produces = "application/json")`
- Apply `@PreAuthorize("hasAuthority('views:write')")`.
- Resolve the caller with existing `getCurrentUserId()`.
- Return the new bulk response DTO.
- Add OpenAPI annotations following the bulk delete endpoint style:
  - Full success example.
  - Partial success example.
  - Validation error response.
  - View-not-found response.

## Phase 5: Tests

- Add `SavedViewServiceTest` cases:
  - Bulk pin with all transactions found.
  - Bulk pin with partial success.
  - Bulk pin removes pinned IDs from exclusions.
  - Bulk exclude with all transactions found.
  - Bulk exclude with partial success.
  - Bulk exclude removes excluded IDs from pins.
  - Non-owned transactions are returned in `notFoundIds`.
  - Nonexistent or soft-deleted transactions are returned in `notFoundIds`.
  - Missing view throws `ResourceNotFoundException`.
- Add WebMvc controller coverage:
  - Valid bulk pin returns `200 OK`.
  - Partial bulk exclude returns `200 OK` with `notFoundIds`.
  - Empty ID list returns `400 Bad Request`.
  - Missing `views:write` returns `403 Forbidden`.
- Add one integration test in `SavedViewServiceIntegrationTest` if practical:
  - Persist a view.
  - Bulk pin or exclude several owned transactions.
  - Reload membership and verify the JSON-backed sets affect results correctly.

## Phase 6: Documentation

- Update `docs/api/README.md` with both new endpoints, request and response
  shapes, permission requirements, and partial-success behavior.
- Update `docs/saved-views.md` with bulk pin and exclude semantics.
- Update `README.md` only if the feature summary needs a public-facing note.

## Phase 7: Verification

Run focused checks first:

```bash
./gradlew test --tests "*SavedViewServiceTest" --tests "*SavedViewControllerAuthorizationTest"
```

Then run the normal service verification:

```bash
./gradlew clean build
```

If `service-common` dependency resolution fails, publish the sibling repository
to Maven Local and retry:

```bash
cd ../service-common
./gradlew clean build publishToMavenLocal
cd ../transaction-service
./gradlew clean build
```
