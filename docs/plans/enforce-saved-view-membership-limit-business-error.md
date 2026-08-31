# Enforce The Saved-View Membership Limit As A Business Error

Make 10,000 the service-owned maximum for both submitted saved-view membership arrays and the
resulting unique membership of a saved view. Violations must return HTTP 422 with error type
`APPLICATION_ERROR` and code `SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED`, while preserving ownership,
atomicity, stale-membership handling, lifecycle locking, and the existing successful 10,000-member
path.

## Phase 1: Add The Service-Owned Limit And Atomic Business Invariant

### Workspace

.

### Goal

Define the public business error and enforce the saved-view membership ceiling in the service
transaction so every caller, including callers outside the HTTP controller, receives the same
behavior.

### Scope

- Add one service-owned compile-time maximum of 10,000 saved-view transactions.
- Add `SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED` to `BudgetAnalyzerError` with an accurate OpenAPI
  description.
- Reject oversized raw create and membership-delta arrays with `BusinessException` before any
  transaction-membership query or write.
- Reject a membership delta when its resulting persisted unique membership would exceed 10,000.
- Add focused PostgreSQL-backed service integration coverage for boundary, rollback, and
  idempotency behavior.

### Non-goals

- Do not change permissions, ownership semantics, trusted-header handling, or not-found behavior.
- Do not add a database column, counter, trigger, constraint, or Flyway migration.
- Do not change the approved saved-view JDBC insertion exception or introduce native SQL.
- Do not modify `service-common` or any other sibling repository.
- Do not increase the 10,000 limit or chunk oversized requests.

### Required context

- Read `AGENTS.md`, `docs/saved-views.md`, `docs/domain-model.md`, and the saved-view section of
  `docs/api/README.md`.
- Read `../service-common/AGENTS.md`, `../service-common/docs/spring-boot-conventions.md`,
  `../service-common/docs/code-quality-standards.md`,
  `../service-common/docs/error-handling.md`, and
  `../service-common/docs/testing-patterns.md` before modifying Java or tests.
- Inspect `SavedViewService`, `BudgetAnalyzerError`, the saved-view service DTOs,
  `SavedViewRepository`, `SavedViewTransactionRepository`,
  `SavedViewTransactionBatchRepository`, and `SavedViewTransactionBatchRepositoryImpl`.
- Inspect `SavedViewServiceIntegrationTest` and the current 10,000-membership persistence test.
- Confirm Docker is available for PostgreSQL Testcontainers. If `service-common` resolution fails,
  use the Maven Local recovery workflow in `AGENTS.md` without editing the sibling repository.

### Execution steps

1. Introduce a service-owned saved-view limit constant that can be reused by service logic and, in
   the next phase, by API schema annotations without creating a `service -> api` dependency.
2. Add `SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED` to `BudgetAnalyzerError`. Use a stable, safe business
   message that states the 10,000 maximum without including submitted transaction IDs, current
   memberships, or other financial data.
3. In `SavedViewService.createView`, check the raw submitted array size before canonicalization or
   transaction locking, then enforce the same maximum against the canonical unique membership
   before any persistence. Throw `BusinessException` with the new code on either violation.
4. In `SavedViewService.updateViewTransactions`, preserve owner-scoped saved-view locking and the
   current overlap and stale-addition checks. Reject either oversized raw delta array before a
   transaction-membership query or write. Apply the otherwise-valid delta inside the existing
   transaction, determine the resulting membership count from `saved_view_transaction`, and throw
   the coded `BusinessException` before touching the saved-view timestamp when the result exceeds
   the maximum. Rely on transaction rollback to undo both additions and removals.
5. Extend `SavedViewServiceIntegrationTest` with real beans and PostgreSQL to prove: exactly 10,000
   unique memberships remain valid; oversized raw create/add/remove arrays return the new code;
   an idempotent re-add at the ceiling succeeds; an effective removal can offset an addition; an
   unknown removal cannot offset a new addition; and a rejected delta preserves membership and
   `updatedAt`.

### Implementation notes

Keep `getLockedOwnedView` first for mutations so a foreign or missing view remains a 404 rather
than exposing mutation validation behavior. Preserve the existing saved-view lifecycle lock as the
serialization point for membership deltas. Perform the resulting-count check after repository
operations but before `touch`; throwing from the transactional service must roll back the complete
delta. Transaction-driven cleanup may only reduce membership and therefore cannot make the limit
unsafe. Do not expose field errors for this business exception, and do not assert the exact
human-readable message in tests; clients must branch on status, type, and code.

### Validation

Run:

```bash
./gradlew test --tests \
  'org.budgetanalyzer.transaction.service.SavedViewServiceIntegrationTest'
```

Inspect the test output for Checkstyle or runtime warnings relevant to the changed code. Confirm
the rejected paths leave no created view, partial membership delta, or timestamp update.

### Completion criteria

The service owns the 10,000 limit, every service entry point raises
`SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED` for its applicable violations, the final unique membership
cannot exceed 10,000 through sequential deltas, and the focused service integration test passes.

## Phase 2: Expose The Business Error Through HTTP And OpenAPI

### Workspace

.

### Goal

Make all three oversized saved-view membership arrays reach the service business rule and publish
the coded 422 response accurately through controller behavior and generated OpenAPI.

### Scope

- Remove Bean Validation interception for only the three 10,000-entry array ceilings.
- Retain all existing required-array, element, positivity, overlap, and nonempty-delta validation.
- Keep `maxItems: 10000` on create, add, and remove arrays using the service-owned constant.
- Document the new code in the create and membership-delta 422 response descriptions.
- Update controller and OpenAPI integration tests for the public contract.

### Non-goals

- Do not change the standardized `ApiErrorResponse` or shared exception handler.
- Do not map unrelated Bean Validation failures to 422; they must remain 400 `VALIDATION_ERROR`.
- Do not change endpoint paths, payload property names, response success statuses, or permissions.
- Do not add controller-owned business validation or another `service -> api` import.

### Required context

- Re-read `AGENTS.md`, especially request validation ownership, API conversion boundaries, and
  authorization rules.
- Read `SavedViewController`, `CreateSavedViewRequest`,
  `UpdateSavedViewTransactionsRequest`, and the service-owned limit introduced in Phase 1.
- Read the saved-view coverage in `SavedViewControllerAuthorizationIntegrationTest` and
  `TransactionOpenApiIntegrationTest`.
- Re-read the Bean Validation and `BusinessException` mappings in
  `../service-common/docs/error-handling.md` and the controller-test rules in
  `../service-common/docs/testing-patterns.md`.
- Confirm Docker is available before running the focused controller tests.

### Execution steps

1. Remove the `@Size(max = 10000)` annotations from the three saved-view transaction-ID arrays so
   the service-owned business check produces the response. Remove or replace the API-local limit
   owner, and reference the service-owned compile-time constant from each `@ArraySchema.maxItems`.
2. Leave `@Valid`, `@NotNull`, positive element validation, overlap validation, and the requirement
   for a nonempty delta intact so syntax and request-shape failures unrelated to the limit continue
   to return 400.
3. Update `SavedViewController` OpenAPI 422 descriptions for create and membership delta to include
   `SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED` alongside the existing saved-view error codes.
4. Change the existing 10,001-entry create/add/remove controller tests to assert HTTP 422, type
   `APPLICATION_ERROR`, code `SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED`, and no `fieldErrors`. Retain
   the exactly-10,000 success cases and avoid exact message assertions.
5. Extend the OpenAPI integration assertions to preserve `maxItems: 10000`, verify the new code is
   advertised by both applicable operations, and keep `ApiErrorResponse` as the 422 schema.

### Implementation notes

The OpenAPI `maxItems` value remains a public declaration of the request ceiling even though the
service, rather than Bean Validation, now enforces it. API request types may depend on a public
service-owned constant because dependencies flow from the API layer toward the service layer; the
service must not import the API request package. Keep other validation errors unchanged to avoid a
global status-code semantic change.

The expected limit response shape is:

```json
{
  "type": "APPLICATION_ERROR",
  "message": "A saved view cannot contain more than 10,000 transactions.",
  "code": "SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED"
}
```

Treat the message as illustrative rather than a stable client key.

### Validation

Run:

```bash
./gradlew test \
  --tests 'org.budgetanalyzer.transaction.api.SavedViewControllerAuthorizationIntegrationTest' \
  --tests 'org.budgetanalyzer.transaction.api.TransactionOpenApiIntegrationTest'
```

Also inspect the generated OpenAPI JSON through the existing integration assertions and confirm an
unrelated invalid saved-view request still returns 400 `VALIDATION_ERROR`.

### Completion criteria

All three oversized arrays return the coded 422 response through the real controller, unrelated
request validation still returns 400, both affected operations advertise the error code, the
OpenAPI array ceilings remain 10,000, and the focused controller/OpenAPI tests pass.

## Phase 3: Prove Concurrency, Update Owner Documentation, And Run Full Validation

### Workspace

.

### Goal

Demonstrate that concurrent membership deltas cannot bypass the business limit, align all active
owner documentation with the final contract, and pass the repository's complete validation gates.

### Scope

- Add focused concurrency coverage around the existing pessimistic saved-view lifecycle lock.
- Update active API, saved-view, and domain documentation.
- Review the cumulative code, test, and documentation diff for layering and sensitive-data safety.
- Run formatting and the complete build in the required order.

### Non-goals

- Do not revise completed or historical plan documents, including
  `docs/plans/bound-saved-view-membership-and-clean-tests.md`.
- Do not change lock ordering, transaction soft-delete cleanup, membership timestamp semantics, or
  the saved-view persistence exception.
- Do not add a migration or claim database-level enforcement of the limit.
- Do not modify sibling repositories.

### Required context

- Re-read `AGENTS.md`, `docs/saved-views.md`, `docs/domain-model.md`, and the saved-view API section
  in `docs/api/README.md`.
- Re-read the saved-view concurrency tests in `SavedViewServiceIntegrationTest` and the lifecycle
  lock contract in `SavedViewRepository`.
- Inspect the cumulative diff and run the intentional/accidental `service -> api` import search
  from `AGENTS.md`.
- Confirm Docker, the Gradle wrapper toolchain, and PostgreSQL Testcontainers are available. Use
  the documented Maven Local recovery workflow only if `service-common` resolution fails.

### Execution steps

1. Add a PostgreSQL-backed concurrency test that starts with a view one membership below the
   ceiling and submits two distinct additions concurrently. Prove the saved-view lock serializes
   the deltas, exactly one reaches 10,000, the other receives
   `SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED`, and the final persisted count is exactly 10,000.
2. Update `docs/saved-views.md` to distinguish the raw per-array ceiling from the final unique
   membership invariant, document the coded 422 response, and state that the lifecycle lock makes
   the check atomic across membership deltas.
3. Update the saved-view sections of `docs/api/README.md` and `docs/domain-model.md` with the same
   maximum and `SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED` contract. State that clients use status,
   type, and code rather than the message, and avoid duplicating implementation details outside
   `docs/saved-views.md`.
4. Review the cumulative change for correct error precedence, rollback, owner isolation, no
   transaction-ID disclosure, no logged financial data, no new persistence exception, no schema
   change, and no unintended service-to-API import.
5. Run the required formatting and full build commands, inspect the complete output, fix every
   Checkstyle warning even if Gradle exits successfully, then run final diff, link, and command
   checks.

### Implementation notes

Use the existing real-bean concurrency-test style with latches, transaction templates, and
PostgreSQL lock observation; do not mock or spy application-owned beans and do not use timing-only
sleep assertions. Reuse efficient sanitized fixtures so the test proves the real 10,000 boundary
without weakening the production constant. The view lock serializes membership deltas; transaction
soft deletion only removes memberships and therefore cannot cause an over-limit result.

No database-schema documentation or migration should be added because enforcement remains an
application transaction invariant. If implementation reveals that the lifecycle lock cannot make
the check atomic, stop and report the missing prerequisite rather than adding an undocumented
locking or schema workaround.

### Validation

Run in sequence:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Inspect the full build output and fix all Checkstyle warnings. Then run:

```bash
git diff --check
rg -n '^import org\.budgetanalyzer\.transaction\.api\.' \
  src/main/java/org/budgetanalyzer/transaction/service \
  src/main/java/org/budgetanalyzer/transaction/repository --glob '*.java'
```

Verify every changed local documentation link and anchor, and syntax-check every changed command.
If Docker, the database container, dependency resolution, or another required verifier is
unavailable, report exactly what did not run and why rather than claiming full verification.

### Completion criteria

Concurrent deltas cannot produce more than 10,000 persisted memberships, all active owner
documentation describes the same coded 422 contract, no forbidden layering or persistence change
was introduced, `clean spotlessApply` and `clean build` pass with no Checkstyle warnings, and final
diff/link/command checks pass.
