# Saved View Clone Endpoint Implementation Plan

Add a dedicated `POST /v1/views/{sourceViewId}/clone` operation that accepts only a new name,
atomically snapshots an owner-scoped source view under the existing lifecycle lock, and returns the
independent target as `201 Created` with `Location: /v1/views/{newViewId}`. The implementation must
reuse saved-view name, membership, ownership, active-transaction, locking, and batch-persistence
rules without introducing a source-to-target relationship or a schema change.

## Phase 1: Implement Transactional Clone Behavior

### Workspace

.

### Goal

Add the service-layer clone operation and prove that it creates an exact, independent target or
rolls back completely when an existing saved-view invariant fails.

### Scope

Introduce a service-layer clone command, implement cloning in `SavedViewService`, reuse the existing
owner-scoped lifecycle lock and membership repositories, and add focused Spring integration tests
to `SavedViewServiceIntegrationTest`.

### Non-goals

Do not add the HTTP route yet. Do not add a source-view field, entity association, migration,
repository-native query, new JDBC exception, cross-user scope, or change to the existing create,
rename, membership-delta, or delete contracts.

### Required context

Read `AGENTS.md`, `docs/saved-views.md`, `docs/domain-model.md`, and the saved-view section of
`docs/api/README.md`. Read `../service-common/AGENTS.md`,
`../service-common/docs/spring-boot-conventions.md`,
`../service-common/docs/code-quality-standards.md`, and
`../service-common/docs/testing-patterns.md` completely before modifying Java or tests. Inspect
`SavedViewService`, `SavedViewConstraints`, `SavedViewRepository`,
`SavedViewTransactionRepository`, `SavedViewTransactionBatchRepositoryImpl`,
`TransactionRepository`, the saved-view domain entities, the service-layer saved-view records, and
`SavedViewServiceIntegrationTest` before editing.

### Execution steps

1. Add a narrowly scoped service-layer command for the clone name under `service/dto`; keep the API
   layer out of service and repository imports.
2. Add a transactional `SavedViewService` clone operation that locks the source with
   `lockByIdAndUserId`, reads its deterministic membership while holding that lock, locks and
   validates those transactions with the existing owner-scoped active-transaction rule, persists a
   new owner-scoped view with the requested name, and batch-inserts the copied membership before
   returning `SavedViewSummary`.
3. Share only the minimal target-persistence logic needed by create and clone so duplicate-name
   translation and portable batch insertion remain centralized; preserve create's raw request-size
   validation and all existing behavior.
4. Extend `SavedViewServiceIntegrationTest` for populated and empty clones, exact copied counts and
   IDs, distinct target identity, post-clone source/target independence, maximum-size source
   membership, owner-scoped not-found behavior, stale membership validation, duplicate-name
   handling, and complete rollback on every failure.
5. Run the focused saved-view service integration test and fix any regression before ending the
   phase.

### Implementation notes

The lock order is source saved-view row first, followed by copied transaction rows in ascending ID
order through `TransactionRepository.lockActiveByOwnerIdAndIdIn`. Read membership only after the
source lock is acquired so a concurrent explicit membership delta resolves entirely before or
after the snapshot. Keep the lifecycle lock until the target view and every membership row commit
in the same transaction.

The clone request has no transaction array, so it has no raw-array limit to validate. A valid
source already satisfies the 10,000 unique-membership invariant; prove that a source at that
boundary clones successfully. Continue to reject a copied membership containing a missing,
soft-deleted, or foreign transaction with the existing `SAVED_VIEW_MEMBERSHIP_STALE` business
error and without exposing transaction IDs. Reuse `persistView` so case-insensitive target-name
conflicts remain `SAVED_VIEW_NAME_ALREADY_EXISTS`. Do not update the source timestamp. Do not store
the source ID on the target or add any continuing coupling between the two views.

### Validation

Run:

```bash
./gradlew test --tests \
  org.budgetanalyzer.transaction.service.SavedViewServiceIntegrationTest
```

Inspect the complete output, including warnings. Confirm failure-path assertions show that neither
a target view nor partial target membership remains after rollback.

### Completion criteria

The service can atomically clone any valid owner-scoped saved view, including an empty or
10,000-member source. The target has a new ID and independent membership, the source is unchanged,
foreign or absent sources are hidden as not found, stale membership and duplicate names use the
existing safe error codes, no schema or non-portable persistence change exists, and the focused
service integration test passes.

## Phase 2: Expose And Verify The Clone HTTP Contract

### Workspace

.

### Goal

Expose the dedicated clone endpoint with validated input, fine-grained authorization, the standard
saved-view response body, and the canonical target `Location` header.

### Scope

Add the clone request record and controller method, document the operation through SpringDoc
annotations, and extend the existing saved-view controller integration coverage using real Spring
beans and claims-header authentication.

### Non-goals

Do not accept `transactionIds` or `sourceViewId` in the JSON body. Do not add conditional behavior
to `POST /v1/views`, a `views:*:any` permission, client code, gateway configuration, or sibling
repository changes.

### Required context

Read `AGENTS.md`, `docs/saved-views.md`, the saved-view section of `docs/api/README.md`, and
`../permission-service/docs/authorization-model.md`. Read `../service-common/AGENTS.md`,
`../service-common/docs/spring-boot-conventions.md`,
`../service-common/docs/code-quality-standards.md`, and
`../service-common/docs/testing-patterns.md` completely before modifying Java or tests. Inspect
`SavedViewController`, `CreateSavedViewRequest`, `UpdateSavedViewRequest`, `SavedViewResponse`,
`SavedViewControllerAuthorizationIntegrationTest`, and the completed phase-1 service API and tests.

### Execution steps

1. Add `CloneSavedViewRequest` as an OpenAPI-annotated API record with one required `name` field,
   matching the existing create/rename trimming, blank-name, and 255-character validation rules.
2. Add `POST /v1/views/{sourceViewId}/clone` to `SavedViewController`, protect it with
   `@PreAuthorize("hasAuthority('views:write')")`, convert the validated request to the internal
   clone command, and return `SavedViewResponse` with HTTP 201.
3. Build the `Location` header from the application context path and canonical
   `/v1/views/{newViewId}` route; do not append the new ID to the current `/clone` request URI.
4. Add SpringDoc response metadata for 201, 400, 404, and the existing 422 stale-membership and
   duplicate-name errors, without exposing inaccessible source or transaction details.
5. Extend `SavedViewControllerAuthorizationIntegrationTest` to cover the exact request and
   five-field response, canonical `Location`, trimmed names, copied membership, invalid name
   payloads, unauthenticated and wrong-permission rejection, `views:write` success, owner-isolated
   404 behavior, and safe 422 error responses; then run the focused controller integration test.

### Implementation notes

The endpoint body is exactly:

```json
{
  "name": "Copy of December review"
}
```

Use the authenticated user ID from `SecurityContextUtil`, never a body field or directly parsed
header. `views:write` is the operation permission; the permission-service grant model already
requires the corresponding own-resource read permission and no cross-user clone behavior exists.
Keep the controller thin and let the service own source lookup, ownership, transaction boundaries,
locking, and persistence. The successful response must use the newly created target ID in both the
body and `Location`, never the source ID.

### Validation

Run:

```bash
./gradlew test --tests \
  org.budgetanalyzer.transaction.api.SavedViewControllerAuthorizationIntegrationTest
./gradlew test --tests \
  org.budgetanalyzer.transaction.service.SavedViewServiceIntegrationTest
```

Inspect all output and warnings. Confirm the endpoint rejects a request without authentication,
rejects `views:read` without `views:write`, does not reveal a foreign source, and never emits
database constraint or transaction diagnostics in a 422 response.

### Completion criteria

`POST /v1/views/{sourceViewId}/clone` accepts only the validated new name, requires `views:write`,
returns 201 with the target `SavedViewResponse`, sets `Location` to
`/v1/views/{newViewId}`, preserves owner isolation and safe errors, and both focused integration
test classes pass.

## Phase 3: Prove Serialization, Document The Contract, And Run Full Validation

### Workspace

.

### Goal

Prove that cloning observes one serialized source state during concurrent membership changes,
publish the endpoint in the owner documentation, and complete every repository validation gate.

### Scope

Add deterministic PostgreSQL concurrency coverage around the saved-view lifecycle lock, update
`docs/saved-views.md` and `docs/api/README.md`, review the complete diff for architectural drift,
and run formatting plus the full build.

### Non-goals

Do not add database schema, deployment, gateway, frontend, permission-service, or orchestration
changes. Do not edit sibling documentation; report any newly discovered sibling requirement to the
user instead.

### Required context

Read `AGENTS.md`, `docs/saved-views.md`, `docs/api/README.md`, and the completed phase-1 and phase-2
diff. Read `../service-common/AGENTS.md`,
`../service-common/docs/code-quality-standards.md`, and
`../service-common/docs/testing-patterns.md` completely before modifying tests. Reinspect the
existing latch, `TransactionTemplate`, PostgreSQL backend PID, and lock-wait helpers in
`SavedViewServiceIntegrationTest`. Review controller annotations and all clone error paths before
updating the API owner documents.

### Execution steps

1. Add deterministic service integration coverage where a membership delta holds the source
   lifecycle lock first, verify clone waits at the database lock, release the delta, and assert the
   target contains the fully committed post-delta membership.
2. Add the reverse ordering: hold the clone transaction after its snapshot and target insertion,
   verify a membership delta waits on the source lifecycle lock, then commit both and assert the
   target remains the pre-delta snapshot while the source contains the later change.
3. Update `docs/saved-views.md` with the clone request, 201 response and `Location` contract,
   `views:write` authorization, owner-scoped not-found behavior, error semantics, lifecycle-lock
   snapshot behavior, active-transaction validation, 10,000-member boundary, and the target's
   permanent independence.
4. Update the Saved Views section of `docs/api/README.md` with the dedicated route and concise
   contract, keeping `docs/saved-views.md` as the detailed behavioral owner rather than duplicating
   its full rationale.
5. Review imports and persistence with repository discovery searches, run focused tests, run the
   required formatting and full-build sequence, inspect Checkstyle warnings even if Gradle exits
   successfully, and validate the documentation diff and links.

### Implementation notes

Use the existing real PostgreSQL lock-observation helpers rather than timing assumptions, mocks,
or spies. Each concurrency branch must have bounded latches, release latches in `finally`, and wait
for both futures so failures cannot leak blocked threads. Assert both source and target membership
after commit, and assert that each target is a separate row with no source relationship.

The documentation must explain that explicit membership deltas and clone share the source
lifecycle lock, so clone observes the state wholly before or wholly after a delta. It must also
state that transaction validation/locking occurs before target persistence completes and that any
failure rolls back the target as one unit. Keep client-side GET-then-POST as compatibility context
only if useful; do not present it as equivalent atomic behavior.

### Validation

Run in this order:

```bash
./gradlew test --tests \
  org.budgetanalyzer.transaction.service.SavedViewServiceIntegrationTest
./gradlew test --tests \
  org.budgetanalyzer.transaction.api.SavedViewControllerAuthorizationIntegrationTest
./gradlew clean spotlessApply
./gradlew clean build
git diff --check -- AGENTS.md README.md docs
```

Inspect the full build output and fix every Checkstyle warning. Verify each changed local Markdown
link and anchor, syntax-check any command added to documentation, and run these repository checks
to confirm no new layer or persistence exception was introduced:

```bash
rg -n '^import org\.budgetanalyzer\.transaction\.api\.' \
  src/main/java/org/budgetanalyzer/transaction/service \
  src/main/java/org/budgetanalyzer/transaction/repository --glob '*.java'
rg -n 'JdbcTemplate|NamedParameterJdbcTemplate|createNativeQuery|nativeQuery\s*=\s*true|ON CONFLICT' \
  src/main/java --glob '*.java'
git diff --stat
git diff -- src/main src/test docs/saved-views.md docs/api/README.md
```

If `service-common` resolution fails, follow the repository's documented Maven Local recovery
build exactly and retry; do not edit the sibling repository. Report any unavailable database,
container runtime, dependency, or verifier rather than claiming full validation.

### Completion criteria

Deterministic integration tests prove both lifecycle-lock orderings and exact source-state
snapshot behavior. The saved-view and API owner documents match the implemented endpoint. No
schema, cross-user, layer-crossing, or unapproved persistence change exists. Focused tests pass,
Spotless has been applied, the clean full build passes without Checkstyle warnings, documentation
checks pass, and every changed file is contained within `transaction-service`.
