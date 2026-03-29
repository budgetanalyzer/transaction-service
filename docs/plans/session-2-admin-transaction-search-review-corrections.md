# Session 2: Admin Transaction Search Review Corrections

## Context

This document captures the corrective work identified during a review of the admin transaction
search implementation against `docs/plans/session-2-admin-transaction-search.md`.

The core Session 2 split is in place:

- `GET /v1/admin/transactions` exists and returns `PagedResponse<AdminTransactionResponse>`.
- `GET /v1/transactions` now uses a user-scoped `getTransactions(userId)` path.
- `TransactionFilter` and `TransactionSpecifications` support `ownerId`.
- Focused controller, service, and specification tests currently pass.

The remaining work is hardening and correctness cleanup around adjacent owner scoping, pagination
guardrails, and request handling.

## Review Findings

### Finding 1. Saved views remain cross-user

`SavedViewService` still resolves matching transactions with `TransactionSpecifications.withFilter(filter)`
and does not scope the query to the saved view owner. `criteriaToFilter()` now sets `ownerId` to
`null`, so saved view membership and counts can still include other users' transactions.

**Affected files**

- `src/main/java/org/budgetanalyzer/transaction/service/SavedViewService.java`
- `src/test/java/org/budgetanalyzer/transaction/service/SavedViewServiceTest.java`

### Finding 2. Admin search lacks pagination and sort guardrails

The admin endpoint sets a default sort, but it still accepts arbitrary sort fields and does not
enforce a configured maximum page size. This falls short of the broader pagination rollout plan.

**Affected files**

- `src/main/java/org/budgetanalyzer/transaction/api/AdminTransactionController.java`
- `src/main/resources/application.yml`
- tests for the admin controller

### Finding 3. Admin search logs raw filter contents

`AdminTransactionController` logs the full `TransactionFilter` at `INFO`. For a cross-user search
endpoint, that is noisier than necessary and risks persisting sensitive search criteria in logs.

**Affected file**

- `src/main/java/org/budgetanalyzer/transaction/api/AdminTransactionController.java`

### Finding 4. Timestamp filter binding is not covered

The new controller tests cover `ownerId`, date, amount, and several text fields, but not the
timestamp filters. `TransactionFilter` currently annotates `Instant` fields with
`@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)`, which is suspicious for timestamp query params and
needs an explicit binding test.

**Affected files**

- `src/main/java/org/budgetanalyzer/transaction/api/request/TransactionFilter.java`
- `src/test/java/org/budgetanalyzer/transaction/api/AdminTransactionControllerTest.java`

### Finding 5. Count endpoint semantics need an explicit decision

The Session 2 split moved admin-wide search to `/v1/admin/transactions`, but the legacy
`GET /v1/transactions/count` path still accepts `isAdmin` and can count across all users. That may
be intentional, but it no longer aligns cleanly with the user-path vs admin-path separation.

**Affected files**

- `src/main/java/org/budgetanalyzer/transaction/api/TransactionController.java`
- `src/main/java/org/budgetanalyzer/transaction/service/TransactionService.java`
- related tests and API docs

## Correction Plan

### Workflow 1. Fix saved view owner scoping ✅

**Goal**: saved views must only resolve transactions owned by the view owner.

**Implementation**

- Update `SavedViewService` so saved view criteria always execute with owner scoping.
- Prefer one of these approaches:
  - Set `ownerId` to `view.getUserId()` when building the `TransactionFilter`.
  - Or compose `TransactionSpecifications.byOwner(view.getUserId())` with the criteria spec at the
    query site.
- Apply the same rule consistently to:
  - `countViewTransactions()`
  - `resolveViewMembership()`

**Tests**

- Add regression tests proving `getViewTransactions()` does not return foreign-owner matched IDs.
- Add regression tests proving `countViewTransactions()` does not count foreign-owner transactions.
- Keep existing pinned/excluded behavior intact for the owning user.

**Status**

- Completed on 2026-03-29.
- `SavedViewService` now enforces owner scoping for matched, pinned, and excluded transaction
  resolution before computing saved view membership or counts.
- `SavedViewServiceTest` includes mixed-owner regression coverage for membership and count results.

### Workflow 2. Harden admin pagination and sorting ✅

**Goal**: make `/v1/admin/transactions` safe for real admin UI usage on large datasets.

**Implementation**

- Add explicit Spring pageable limits in `application.yml`.
- Set `spring.data.web.pageable.max-page-size=100`.
- Set `spring.data.web.pageable.default-page-size=50` if the service should standardize the same
  default outside the controller annotation.
- Constrain allowed sort fields instead of passing arbitrary sort properties through.
- Preserve deterministic default ordering with `date,DESC` then `id,DESC`.
- Decide whether the implementation should reject unsupported sort fields with `400` or sanitize to a
  safe subset. Rejecting invalid fields is preferred because it is easier to reason about.

**Tests**

- Add controller tests for unsupported sort fields.
- Add controller or integration tests for page-size caps.
- Keep the existing default-sort tests.

**Status**

- Completed on 2026-03-29.
- `application.yml` now sets `spring.data.web.pageable.default-page-size=50` and
  `spring.data.web.pageable.max-page-size=100`.
- `AdminTransactionController` now rejects unsupported sort fields with `400 Invalid Request` and
  continues to default to `date,DESC` then `id,DESC`.
- `AdminTransactionControllerTest` covers the invalid-sort and page-size-cap cases.

### Workflow 3. Remove raw filter logging ✅

**Goal**: avoid logging full cross-user search criteria.

**Implementation**

- Replace `log.info("Admin transaction search - filter: {} page: {}", filter, pageable)` with a
  lower-sensitivity log statement.
- Log only structural metadata if logging is still useful, for example page number, page size, and
  whether specific filters are present.
- Keep `ownerId`, free-text description, and similar query values out of INFO logs.

**Tests**

- No dedicated tests required unless the project already validates structured logging.

**Status**

- Completed on 2026-03-29.
- `AdminTransactionController` no longer logs the raw `TransactionFilter` or `Pageable` object at
  `INFO`.
- The INFO log now includes only structural request metadata: page number, page size, sort, and
  coarse filter-presence flags.

### Workflow 4. Verify and correct timestamp filter binding ✅

**Goal**: ensure timestamp query params bind correctly for admin search.

**Implementation**

- Add controller tests covering:
  - `createdAfter`
  - `createdBefore`
  - `updatedAfter`
  - `updatedBefore`
- If binding fails, change the `Instant` fields in `TransactionFilter` to
  `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)`.
- Verify the specification still applies inclusive comparisons as intended.

**Tests**

- Add binding assertions in `AdminTransactionControllerTest`.
- Add any needed integration coverage if MockMvc alone is not sufficient.

**Status**

- Completed on 2026-03-29.
- `TransactionFilter` now binds `createdAfter`, `createdBefore`, `updatedAfter`, and
  `updatedBefore` as ISO-8601 date-time query params via `DateTimeFormat.ISO.DATE_TIME`.
- `AdminTransactionControllerTest` now asserts all four timestamp filters bind to the expected
  `Instant` values.
- `TransactionSpecifications` was verified to keep inclusive timestamp comparisons via
  `greaterThanOrEqualTo` and `lessThanOrEqualTo`.

### Workflow 5. Decide and implement count endpoint semantics ✅

**Goal**: make `/v1/transactions/count` behavior explicit and consistent.

**Decision options**

- Option A: keep admin-wide counts on the legacy endpoint and document that behavior clearly.
- Option B: scope the legacy count endpoint to the requesting user only and introduce an admin count
  path under `/v1/admin/transactions` if the UI needs one.

**Recommendation**

- Prefer Option B for consistency with the Session 2 split. The non-admin path should stay user
  scoped, and admin-wide behavior should live under `/v1/admin/**`.

**Tests**

- Update controller and service tests to reflect the chosen behavior.
- Add authorization coverage if a new admin count path is introduced.

**Status**

- Completed on 2026-03-29.
- Adopted Option B.
- `GET /v1/transactions/count` now always scopes counts to the requesting user via
  `TransactionService.countActiveForUser(...)`, even for callers with the `ADMIN` role.
- Added `GET /v1/admin/transactions/count` in `AdminTransactionController` for admin-wide counts.
- Added controller, authorization, and service regression coverage for the split count behavior.

### Workflow 6. Update documentation ✅

**Goal**: keep the behavior and guardrails documented in the same change.

**Documentation updates**

- Update `README.md` or a service doc to include:
  - `GET /v1/admin/transactions`
  - paged response contract
  - page size and sort constraints
  - any admin count behavior, if retained or added
- Update this plan or add a short implementation note after the work lands.

**Status**

- Completed on 2026-03-29.
- `README.md` now documents the split between user-scoped and admin-wide count endpoints.
- `docs/api/README.md` now lists both count endpoints, the admin search/count guardrails, the
  stable `PagedResponse` contract, and the claims-header authorization requirements for user and
  admin transaction endpoints.

## Recommended Execution Order

1. Fix saved view owner scoping and add regression tests.
2. Harden admin sorting and page-size limits.
3. Remove raw filter logging.
4. Add timestamp binding tests and correct annotations if needed.
5. Resolve count endpoint semantics and update tests.
6. Update user-facing documentation.

## Verification

Run at least the focused suites below after implementing the corrections:

```bash
./gradlew test --tests "org.budgetanalyzer.transaction.service.SavedViewServiceTest"
./gradlew test --tests "org.budgetanalyzer.transaction.api.AdminTransactionControllerTest"
./gradlew test --tests "org.budgetanalyzer.transaction.api.AdminTransactionControllerAuthorizationTest"
./gradlew test --tests "org.budgetanalyzer.transaction.service.TransactionServiceTest"
./gradlew test --tests "org.budgetanalyzer.transaction.api.TransactionControllerTest"
./gradlew test --tests "org.budgetanalyzer.transaction.api.TransactionControllerAuthorizationTest"
./gradlew test --tests "org.budgetanalyzer.transaction.repository.spec.TransactionSpecificationsIntegrationTest"
```

Before merge, run the full service build:

```bash
./gradlew clean build
```
