# Backend-Owned Saved-View Create-From-Source Plan

Extend ordinary transaction-service saved-view creation with an optional `sourceViewId` query
parameter. The request accepts the complete target view definition and derives initial pin and
exclusion changes entirely on the backend. The new view remains an independent, dynamic saved view
with no source relationship: its ordinary membership uses the submitted target criteria, its pins
are the active source pins that satisfy filters changed from the source definition, and all stored
source exclusions are copied. Omitting `sourceViewId` preserves ordinary creation behavior.

Keep the implementation explicit and small. Reuse the existing create request and
`SavedViewCommand`; add one pure criteria reconciler, one reusable transaction-ID specification,
and one transactional service operation. Do not send transaction membership IDs from the client,
add a view hierarchy, materialize membership, build a generic criteria-expression framework, or
optimize before measured transaction volumes require it. Execute the phases in order because each
phase relies on the focused behavior established by the prior checkpoint.

The defining formulas are:

```text
target criteria   = complete criteria submitted by the client
changed filters   = target constraints whose effective values differ from the source
target pins       = active owner-scoped source pins matching changed filters
target exclusions = all stored source exclusions
```

## Phase 1: Define and Prove Criteria Reconciliation

### Workspace

.

### Goal

Implement a small, pure criteria reconciler that converts source and target saved-view definitions
into the exact filter constraints that changed and therefore apply to source pins.

### Scope

- Add a dedicated `ViewCriteriaReconciler` with one public operation that returns a
  `ViewCriteria` containing only changed target constraints.
- Include `openEnded` when determining the effective `dateTo` constraint.
- Keep reconciliation as an explicit field-to-field mapping with small equivalence helpers.
- Compare filters according to their effective matching semantics where raw Java equality would
  produce a false change.
- Add exhaustive, framework-free unit tests for every saved-view criterion and open-ended date
  behavior.

### Non-goals

- Creating or saving a view in this phase.
- Querying transactions or adding JPA behavior.
- Combining source and target criteria to produce target membership. The submitted target criteria
  remain the complete definition that will be persisted.
- Returning a generic change map, field-name list, expression tree, predicate AST, or reflection-
  based structure.
- Adding inheritance, parent IDs, immutable snapshots, or frontend-supplied membership IDs.
- Refactoring the existing transaction specification builder or changing matching semantics.

### Required context

- Read `AGENTS.md` and confirm this feature has no sibling-repository prerequisite. Existing
  service-common validation, exception, and security capabilities are sufficient.
- Before changing Java, read `../service-common/docs/code-quality-standards.md` completely.
- Read `../service-common/docs/testing-patterns.md` for pure unit-test conventions.
- Review `ViewCriteria`, `TransactionCriteria.fromViewCriteria(...)`,
  `TransactionSpecifications.withCriteria(...)`, and `TransactionCriteriaTest` before editing.
- Trace the current effective semantics for blank/null multi-value fields, case-insensitive text,
  multi-word OR matching, case-insensitive currency equality, numeric ranges, and open-ended
  `dateTo` resolution. Reconciliation equivalence must not silently disagree with those semantics.
- If service-common cannot be resolved, stop and report the documented prerequisite. Do not switch
  repositories from this phase or work around missing shared artifacts.

### Execution steps

1. Add a final, stateless `ViewCriteriaReconciler` in the service layer with a clearly named public
   method such as `changedConstraints(...)`. Accept source criteria/open-ended state, target
   criteria/open-ended state, and an explicit `LocalDate evaluationDate` so open-ended behavior is
   deterministic in tests and the caller captures the current date once.
2. Implement `changedConstraints(...)` as one direct `new ViewCriteria(...)` call in record-field
   order. For each field, call a small selector equivalent to “return the target value when source
   and target are not semantically equivalent; otherwise return null.” Do not use a loop,
   reflection, switch over field names, mutable accumulator, or nested if/else chain.
3. Define focused private equivalence helpers instead of one branch-heavy comparator:
   use ordinary equality for dates and transaction type; numeric comparison for amounts so scale-
   only differences are unchanged; canonical case-insensitive exact sets for currencies; and
   canonical case-insensitive OR-term sets for account IDs, bank names, and search text. Treat
   null, empty, and blank-only filters as the same absent constraint wherever the existing query
   path already does so.
4. Resolve the source and target effective `dateTo` values in one small helper: an explicit
   `dateTo` wins; otherwise `openEnded=true` resolves to `evaluationDate`; otherwise it is absent.
   Feed those effective values through the same changed-target selector. A transition from closed
   to open-ended can therefore add the current-date constraint, while removing open-ended behavior
   adds no pin constraint.
5. Add `ViewCriteriaReconcilerTest` as a pure unit test. Prove identical effective criteria return
   `ViewCriteria.empty()`, then cover each of the nine fields independently so a constructor-order
   mistake cannot pass unnoticed. Use either explicit tests or a small readable parameter table;
   do not build a reflection-driven test framework.
6. Add focused reconciliation cases for changed-to-null removal, null/empty/blank equivalence,
   set-order equivalence, case-only equivalence for case-insensitive fields, search-word order and
   whitespace equivalence under OR semantics, numerically equal `BigDecimal` values with different
   scales, genuinely changed values, and both directions of the open-ended transition using a
   fixed evaluation date.
7. Add a mixed-field test proving changed fields retain target values while unchanged fields are
   null in the returned pin filter. Include a target removal alongside an added constraint to
   prove removal does not accidentally reapply the source value.

### Implementation notes

- `null` in the reconciled result means “this field imposes no new constraint on source pins.” It
  can mean either unchanged or explicitly removed; those cases intentionally have the same pin-
  filtering effect.
- Return the target's original value for a genuinely changed field. Canonical forms are for
  equivalence comparison only and must not replace user-facing values that will be interpreted by
  the existing specification path.
- Text canonicalization should mirror existing OR-term behavior, not invent stricter AND matching.
  Account and bank set elements, plus search text words, ultimately contribute case-insensitive OR
  terms in the current specification.
- Keep the helper concrete to `ViewCriteria`. A generic reconciliation engine would obscure nine
  stable fields and make future behavior harder to audit.
- When a criterion is added later, the compiler will require updating the direct `ViewCriteria`
  constructor call. Add a corresponding focused test in the same change.

### Validation

Run the repository-required formatting and build sequence and inspect all compiler, Checkstyle,
Javadoc, test, and Spotless output:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Inspect `ViewCriteriaReconciler` directly and confirm the public method is an explicit field
mapping supported by small helpers, with no reflective dispatch, mutable field loop, or branching
tree.

### Completion criteria

- A pure reconciler returns only effective target constraints that changed from the source.
- Every current `ViewCriteria` field and both open-ended transition directions have focused tests.
- Equivalent case, whitespace, set order, empty values, and amount scale do not create false filter
  changes under existing matching semantics.
- Removed criteria impose no pin constraint, and changed criteria retain target values.
- The implementation is a direct auditable field mapping rather than generic or branch-heavy
  machinery.
- No persistence, API, or membership behavior changes in this phase.
- The clean format/build sequence passes.

## Phase 2: Implement the Atomic Backend Create-From-Source Operation

### Workspace

.

### Goal

Use the reconciled changed constraints to create an independent view atomically, with the backend
selecting retained pins and copying every stored exclusion.

### Scope

- Add one transaction-ID specification that composes with existing transaction criteria.
- Add one owner-scoped, transactional `SavedViewService.createViewFromSource(...)` operation.
- Reuse `SavedViewCommand` as the complete target definition.
- Query active source pins through the existing saved-view filter semantics plus an ID constraint.
- Copy all stored source exclusions and leave the source unchanged.
- Add focused service tests for orchestration and edge behavior.

### Non-goals

- Adding an HTTP endpoint in this phase.
- Adding a create-from-source-specific request or service command with duplicate fields.
- Asking the frontend to identify visible transactions or pins.
- Persisting source lineage or static membership.
- Adding a custom repository query, native SQL, cache, batching strategy, paging protocol, or
  schema migration.
- Optimizing the existing response-count resolution that occurs after creation.

### Required context

- Confirm Phase 1 is complete and its build passes.
- Read `AGENTS.md`, `../service-common/docs/code-quality-standards.md`, and
  `../service-common/docs/testing-patterns.md` before changing Java.
- Review the completed `ViewCriteriaReconciler` and its tests.
- Review `SavedViewService`, `SavedViewCommand`, `SavedView`, `TransactionRepository`,
  `TransactionSpecifications`, `SavedViewServiceTest`, and the soft-delete behavior supplied by
  `findAllNotDeleted(...)`.
- Confirm `TransactionCriteria.fromViewCriteria(...)` remains the one conversion from saved-view
  fields to repository-query semantics. Do not reproduce JPA predicates in the service.
- If service-common cannot be resolved, stop and report the prerequisite rather than changing the
  workspace or bypassing shared behavior.

### Execution steps

1. Add `TransactionSpecifications.byIds(Collection<Long> ids)` as a small composable
   specification using `root.get("id").in(ids)`. Require a non-null collection and document the
   method; the create-from-source service will skip the transaction query for an empty source pin
   set, so do not add a special query or premature empty-list optimization inside the
   specification.
2. Add `SavedViewService.createViewFromSource(UUID sourceViewId, String userId,
   SavedViewCommand targetCommand)` and mark it `@Transactional`. Load the source with the existing
   `getView(sourceViewId, userId)` method so missing and foreign-owned sources retain the same
   indistinguishable 404 behavior.
3. Capture `LocalDate.now()` once for the operation and call the Phase 1 reconciler with source and
   target criteria/open-ended state. Convert the reconciled result through
   `TransactionCriteria.fromViewCriteria(changedConstraints, userId, false)`; the reconciler has
   already made any effective open-ended upper bound explicit.
4. If the source has pins, compose `TransactionSpecifications.withCriteria(...)` with
   `TransactionSpecifications.byIds(source.getPinnedIds())`, then call
   `findAllNotDeleted(...)`. Collect the returned IDs as the target pin set. This single query must
   enforce source-pin identity, authenticated ownership, soft-delete state, and changed filters.
   If the source has no pins, use an empty mutable set and issue no transaction query.
5. Construct one new `SavedView` explicitly with the authenticated owner and the command-supplied
   name, complete target criteria, and open-ended state. Assign a new mutable set of retained pin
   IDs and a separate mutable copy of every raw stored source exclusion. Save the target once;
   never mutate or save the source.
6. Extend `SavedViewServiceTest` to prove command-supplied target fields, one target save, source
   immutability, non-aliased override sets, transaction-query skipping for no pins, and all raw
   exclusion copying, including inactive historical IDs.
7. Add service cases in which the repository returns only a subset of source pins, proving only
   query results become target pins. Cover a missing/foreign source with 404 and no transaction
   query/save. Use Phase 1 tests for field-level reconciliation; do not duplicate all nine field
   cases through mocked service tests.

### Implementation notes

- The database query, not the frontend, decides which source pins are active, owner-scoped, and
  visible under changed constraints.
- Combining the changed-constraint specification with source pin IDs is intentionally direct. Do
  not load all pinned transactions and reimplement filtering in Java.
- An empty changed-constraint object matches every active owner-scoped source pin. This gives an
  unmodified source-assisted creation the expected full active-pin copy while still dropping
  soft-deleted or foreign-owner historical pins.
- Pins retain their override of unchanged source criteria. Only criteria whose effective target
  values changed can hide them.
- Copy exclusions from the raw stored set. Hidden exclusions do not change initial membership but
  preserve deliberate vetoes if target criteria are broadened or transactions are edited later.
- Direct construction is preferred over a generic entity-clone abstraction. A tiny private helper
  shared with ordinary create is acceptable only if it makes the two creation paths clearer.

### Validation

Run the required repository sequence:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Confirm the create-from-source path contains one owner-scoped source lookup, at most one
changed-filter pin query, and one target save. Verify no custom repository query, migration, or
source-view field was added.

### Completion criteria

- One transactional service call creates an independent target owned by the authenticated user.
- The target stores the complete command criteria/open-ended state without backend criteria
  merging.
- Retained pins are selected by one database specification combining source IDs, ownership,
  active state, and reconciled changed filters.
- No-change source-assisted creation copies every active source pin; changed filters omit pins that
  no longer match.
- Every stored source exclusion is copied, including currently irrelevant and historical IDs.
- Source state is unchanged and target override collections do not alias source collections.
- Missing/foreign sources produce the existing 404 behavior without downstream work.
- The clean format/build sequence passes.

## Phase 3: Expose Source-Assisted Creation Through the Existing API

### Workspace

.

### Goal

Expose source-assisted creation through the existing bounded create request and document that the
backend owns all pin/exclusion reconciliation when `sourceViewId` is supplied.

### Scope

- Add optional `sourceViewId` query-parameter handling to `POST /v1/views` in
  `SavedViewController`.
- Reuse `CreateSavedViewRequest` and `SavedViewResponse`.
- Require `views:write`, return `201 Created`, and point `Location` to the new view.
- Add controller authorization, mapping, validation, response, and error tests.
- Add generated OpenAPI assertions and update the saved-view API reference.

### Non-goals

- Adding transaction or pin ID arrays to the request.
- Adding a new request record identical to `CreateSavedViewRequest`.
- Accepting partial criteria, criteria patches, refinement criteria, or backend merge instructions.
- Exposing raw persisted override arrays in a response.
- Adding a child, clone, copy, variant, save-as, or other dedicated endpoint; a new permission; a
  new error code; or a partial-success response.
- Changing ordinary `POST /v1/views` behavior when `sourceViewId` is omitted.

### Required context

- Confirm Phases 1 and 2 are complete and their clean builds pass.
- Read `AGENTS.md`, `../service-common/docs/code-quality-standards.md`,
  `../service-common/docs/testing-patterns.md`, and `../service-common/docs/error-handling.md`
  before changing Java.
- Review `CreateSavedViewRequest`, `ViewCriteriaApi`, `SavedViewController`,
  `SavedViewControllerAuthorizationTest`, and `TransactionOpenApiIntegrationTest`.
- Review the Saved Views section in `docs/api/README.md`; update it in this phase so the generated
  contract and maintained endpoint reference remain aligned.
- If service-common cannot be resolved, stop and report the prerequisite instead of weakening
  validation or security coverage.

### Execution steps

1. Extend the existing `POST /v1/views` operation with
   `@RequestParam(name = "sourceViewId", required = false) UUID sourceViewId`, retaining the
   existing validated `CreateSavedViewRequest` body and `views:write` guard. Keep the controller
   thin: obtain the authenticated user, convert the complete API criteria at the boundary,
   construct the existing `SavedViewCommand`, call `createView(...)` when the parameter is absent
   or `createViewFromSource(...)` when it is present, and use the existing response mapping.
2. Return `201 Created` in both modes with a `Location` ending in `/v1/views/{newViewId}` and
   including the application context path. Document ordinary creation and source-assisted
   creation on the same generated operation, including 201, malformed-UUID or body-validation
   400, and owner-scoped 404 responses with existing response schemas.
3. Write the OpenAPI operation description as a backend-owned contract: the request contains the
   complete target criteria; unchanged source criteria continue to be overridden by pins; changed
   effective filters are applied by the backend to active source pins; all stored exclusions are
   copied; and the target has no ongoing source relationship.
4. Extend `SavedViewControllerAuthorizationTest` to cover absent and valid `sourceViewId` modes,
   exact request-to-command mapping, the top-level target `Location`, resolved response counts,
   unauthenticated 401, missing `views:write` 403, successful write authorization, malformed UUID
   and reused-request validation 400 responses, and owner-scoped service 404 mapping. Assert stable
   status/type/field paths rather than message text.
5. Extend `TransactionOpenApiIntegrationTest` to prove the single create operation documents the
   optional UUID `sourceViewId` query parameter, references `CreateSavedViewRequest`, exposes only
   name/criteria/open-ended target-definition fields, exposes no membership-ID field, and declares
   the intended 201/400/404 responses. Assert the operation description covers ordinary creation,
   selective pins, all exclusions, independent-view semantics, and complete target criteria.
6. Update `docs/api/README.md` with both modes of `POST /v1/views`, the optional `sourceViewId`
   parameter, permission, bounded request example, 201 response, and concise backend reconciliation
   semantics. Explicitly state that `criteria` is the complete target definition rather than a
   delta and that the backend does not combine it with source criteria for ordinary membership.

### Implementation notes

- Reusing the create request is intentional because both modes accept exactly one complete
  saved-view definition. Source override reconciliation is selected by the optional query
  parameter, not by conditional request fields.
- `views:write` is consistent with existing pin/exclude operations. Ownership still comes from the
  security context and owner-scoped source lookup; never accept a user ID in the body.
- The request size is independent of transaction count and pin/exclusion count.
- The operation is ordinary top-level creation, not a child, clone, copy, save-as, variant, or
  lineage operation. The parameter supplies only the source view UUID used to initialize pins and
  exclusions.
- Keep using `SavedViewResponse`; its counts remain resolved active counts rather than raw stored
  array sizes.

### Validation

Run the required repository sequence and inspect generated OpenAPI assertions:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Confirm the generated schema contains no transaction membership array and the operation description
is sufficient for the web repository to implement the workflow without inferring backend rules.

### Completion criteria

- `POST /v1/views` accepts the existing bounded create request and selects the ordinary or
  source-assisted service operation exactly once according to whether `sourceViewId` is absent or
  present.
- It returns 201, a correct new-resource `Location`, and the standard saved-view response.
- Authentication, `views:write`, owner-scoped 404, and request validation use existing conventions.
- OpenAPI and `docs/api/README.md` document both modes on the single create operation and state that
  criteria are complete, pin selection is backend-owned, all exclusions are copied, and the target
  is independent.
- No membership IDs, duplicate request model, new permission, or new error type appear.
- The clean format/build sequence passes.

## Phase 4: Prove Persisted Create-From-Source Behavior and Reconcile Documentation

### Workspace

.

### Goal

Prove reconciliation and source-assisted creation behavior against PostgreSQL, document the
durable semantics next to existing membership rules, and perform final repository-wide
verification without broadening the architecture.

### Scope

- Add PostgreSQL integration coverage for changed-filter pin selection across representative
  criteria types.
- Prove no-change, selective-pin, all-exclusion, ownership, soft-delete, and future-broadening
  behavior.
- Prove ordinary target criteria remain dynamic after source-assisted creation.
- Update canonical saved-view behavior documentation and audit related domain/schema text.
- Run final formatting, static checks, tests, and build.

### Non-goals

- Adding web tests or changing another repository.
- Repeating all nine pure reconciliation cases at the integration layer.
- Adding immutable membership, parent-child behavior, lineage, migrations, indexes, caching,
  concurrency tokens, benchmarks, or large-data optimizations.
- Refactoring unrelated saved-view code or historical test naming/style debt.

### Required context

- Confirm Phases 1 through 3 are complete and their clean builds pass.
- Read `AGENTS.md`, `../service-common/docs/code-quality-standards.md`, and
  `../service-common/docs/testing-patterns.md` before changing Java tests.
- Review `ViewCriteriaReconcilerTest`, the completed create-from-source service/API path,
  `SavedViewServiceIntegrationTest`, and its PostgreSQL/Testcontainers setup.
- Re-read `docs/saved-views.md`, the SavedView section of `docs/domain-model.md`, the `saved_view`
  section of `docs/database-schema.md`, and the create-from-source entry in
  `docs/api/README.md`.
- Confirm no implementation change is needed in service-common, permission-service, orchestration,
  or the web repository. Stop and report if that prerequisite assumption becomes false.

### Execution steps

1. Add a PostgreSQL integration scenario whose source has multiple active pins outside its saved
   criteria and whose target changes one representative set/text filter. Prove matching source pins
   are retained, nonmatching source pins are omitted, ordinary non-pin transactions cannot become
   pins, and the source row remains unchanged.
2. Add a no-change create-from-source case proving every active source pin is copied even when
   those pins do not match unchanged source criteria. Include a soft-deleted historical source pin
   and prove it is not copied as an active target pin.
3. Include source exclusions inside and outside the target criteria, plus a stored historical
   exclusion. Reload the target row and prove every raw exclusion ID was copied. Broaden the target
   criteria so the previously irrelevant active exclusion would otherwise match, then prove it
   remains vetoed.
4. Cover one range/open-ended integration path with a fixed-enough transaction date arrangement to
   prove the reconciled effective upper/lower bound is actually enforced by the composed JPA
   specification. Rely on Phase 1 unit tests for the remaining individual fields and equivalence
   cases.
5. Insert a new transaction matching the target criteria after creation and prove it enters target
   membership normally. This establishes that the operation snapshots definition and curation,
   not immutable transaction membership.
6. Update `docs/saved-views.md` with a Create From Source section containing the defining formulas,
   complete-target request semantics, changed-filter rules for pins, all-stored-exclusion behavior,
   owner/soft-delete handling, dynamic future membership, and lack of a persistent source
   relationship.
7. Update `docs/domain-model.md` only as needed to describe the create-from-source business rule
   without claiming a new entity relationship. Audit `docs/database-schema.md` and leave it
   unchanged if accurate; do not manufacture a migration or schema edit. Reconcile any duplicate
   wording in `docs/api/README.md` with the canonical saved-view document.
8. Audit changed Java for wildcard or Hibernate imports, explicit local types where `var` is
   appropriate, abbreviated fields, forbidden `*Dto` names, missing Javadoc periods, and new
   suppressions. Search affected code and docs for stale claims about client-submitted membership,
   criteria merging, save-as or variant terminology, full cloning, parent-child relationships, or
   immutable snapshots.

### Implementation notes

- Unit tests own exhaustive reconciliation truth-table coverage. Integration tests prove that a
  few representative reconciled constraints compose correctly with source pin IDs, ownership, and
  soft deletion in PostgreSQL.
- Persisted-set assertions are required. Response-only checks could miss dropped irrelevant or
  historical exclusions because response counts include only active overrides.
- The future-broadening exclusion case is the regression proving why all exclusions are copied.
- Capture current date once inside source-assisted creation. Integration dates should avoid
  midnight-sensitive assertions; deterministic boundary detail belongs in the reconciler's
  fixed-date unit tests.
- Do not add optimistic concurrency now. The service uses the owner-scoped source state read in its
  transaction and the submitted target definition. Address observed concurrent edits as a separate
  versioning feature.
- Do not change `AGENTS.md` unless implementation instructions, guardrails, or discovery commands
  actually change.

### Validation

Run the repository-required sequence and inspect all output for warnings and failures:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Then inspect the affected contract and documentation without using git write operations:

```bash
rg -n "create.from.source|source-assisted|changedConstraints|changed filters|all stored.*exclusion" \
  src/main/java src/test/java docs/api/README.md docs/saved-views.md docs/domain-model.md
rg -n "visibleTransactionIds|visiblePinnedIds|parent|child|materialized|immutable membership" \
  src/main/java src/test/java docs/api/README.md docs/saved-views.md docs/domain-model.md
```

Every second-search result must either be removed or explicitly describe a non-goal. Confirm there
is no new Flyway migration, persistent source-view field, dedicated create-from-source request
model, or child creation path.

### Completion criteria

- PostgreSQL coverage proves changed criteria select retained pins through backend query semantics,
  while unchanged criteria preserve pin overrides.
- Soft-deleted/foreign source pins are not copied, arbitrary matching transactions are not promoted
  to pins, and the source remains unchanged.
- Every raw source exclusion is copied and remains effective after target broadening.
- New criteria matches can enter later, confirming the target is dynamic rather than materialized.
- Pure reconciler tests cover every field and semantic-equivalence edge; integration tests cover
  representative JPA composition without duplicating the full matrix.
- API, saved-view, and domain documentation agree with implemented backend-owned reconciliation;
  database documentation remains accurate without a migration.
- No client membership IDs, hierarchy, alternate resolver, early optimization, or unrelated
  refactor has entered the change.
- The clean format/build sequence passes without warnings.
