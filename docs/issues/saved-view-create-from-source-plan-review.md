# Saved-View Create-From-Source Plan Review Issues

**Status:** Open  
**Reviewed plan:** [`docs/plans/saved-view-save-as.md`](../plans/saved-view-save-as.md)  
**Purpose:** Record the decisions and plan corrections required before implementation begins.

The proposed architecture is sound: the request remains independent of transaction volume, the
backend owns override reconciliation, source lookup is owner-scoped, the target remains a dynamic
independent view, and no schema change is expected. The issues below capture every ambiguity and
execution concern found during the pre-implementation review.

Do not begin the plan unchanged. Resolve each numbered issue or explicitly record why no plan
change is necessary.

## Resolution Checklist

- [x] SVV-001: Use ordinary create with an optional `sourceViewId` query parameter.
- [ ] SVV-002: Define whether text-filter equivalence is normalized or logically complete.
- [ ] SVV-003: Specify exact normalization rules for every string-based criterion.
- [ ] SVV-004: Confirm that pins continue to override every unchanged target filter.
- [ ] SVV-005: Confirm the asymmetric treatment of historical pins and exclusions.
- [ ] SVV-006: Define the concurrency guarantee behind the word atomic.
- [ ] SVV-007: Move behavioral documentation into the same phase as each behavior change.
- [ ] SVV-008: Split oversized AI Session Handler phases.
- [ ] SVV-009: Resolve the contradictory internal-mocking test guidance.
- [ ] SVV-010: Reconcile service-common recovery instructions with phase workspace boundaries.
- [ ] SVV-011: Add explicit foreign-owner stored-pin integration coverage.
- [ ] SVV-012: Correct the existing database documentation for open-ended views.
- [ ] SVV-013: Scope query-count assertions to creation rather than the complete HTTP response.

## SVV-001: Feature and Endpoint Terminology

**Status:** Resolved  
**Type:** Product and API naming decision

### Decision

This is ordinary top-level saved-view creation with an optional source for initial pin and exclusion
reconciliation:

```http
POST /v1/views
POST /v1/views?sourceViewId={uuid}
```

The request body remains the complete `CreateSavedViewRequest` in both cases. The optional query
parameter changes only how the backend initializes pins and exclusions. It does not create a child
resource, hierarchy, lineage field, or ongoing source relationship.

Use `sourceViewId`, not `sourceView`, because the parameter carries the source view's UUID rather
than an embedded view or another source representation.

### Contract

```text
POST /v1/views
  -> ordinary creation with empty stored pin and exclusion sets

POST /v1/views?sourceViewId={uuid}
  -> ordinary creation whose initial pins and exclusions are reconciled from the source view
```

- The body is always the complete target definition, never a criteria delta.
- A missing or foreign-owned `sourceViewId` returns the existing owner-scoped 404 response.
- A malformed UUID query value returns 400 through existing request binding behavior.
- The created view has a new ID and no stored source ID or relationship.
- All later reads and mutations address the new view as an ordinary top-level `/v1/views/{id}`
  resource.
- Omitting `sourceViewId` preserves the current create behavior.
- The generated OpenAPI operation must document both modes on the single create operation.

A successful request in either mode returns `201 Created` and points `Location` to the new
top-level view:

```http
Location: /transaction-service/v1/views/{newViewId}
```

The application context path remains part of the internal service URI. External gateway rewriting
does not change the controller's top-level resource semantics.

### Service boundary

Keep two explicit service operations rather than passing a nullable mode selector into ordinary
creation:

```java
createView(userId, command)
createViewFromSource(sourceViewId, userId, command)
```

The controller owns the optional query parameter and selects one service operation. This keeps
conditional HTTP behavior at the boundary and leaves existing service callers unambiguous.

### Rejected alternatives

Child paths such as `/v1/views/{sourceViewId}/variants`, `/derived-views`, `/copies`, or `/copy`
incorrectly imply that the new view remains subordinate to the source. Terms such as `save-as`,
`clone`, `duplicate`, `fork`, and `version` are also rejected because they imply a UI command, exact
copying, lineage, branching, or history that the feature does not provide.

### Plan impact

Revise the plan title and prose to use **create view from source** or **source-assisted creation**.
Replace the dedicated child endpoint with this optional parameter on the existing create endpoint:

```java
@RequestParam(name = "sourceViewId", required = false) UUID sourceViewId
```

Retain the existing request and response models.

Update controller and OpenAPI tests to cover both modes of the same operation, including absence,
valid source, malformed UUID, owner-scoped 404, authorization, validation, command mapping, and the
top-level `Location`. Remove assertions and searches tied to a `/save-as` or `/variants` path.

## SVV-002: Meaning of Effective Criteria Equivalence

**Status:** Open  
**Type:** Behavioral correctness decision

### Ambiguity

The plan requires comparison according to "effective matching semantics" but proposes canonical
OR-term set equality. Those rules are not equivalent because the repository uses substring
predicates.

For example:

```text
source searchText = "coffee"
target searchText = "coffee coffeehouse"
```

The target adds a redundant term. Both predicates match the same descriptions because everything
containing `coffeehouse` also contains `coffee`. Canonical term sets differ, however. Treating the
filter as changed would apply it to source pins and could remove a pin that previously overrode the
logically unchanged filter.

The same problem can occur in account and bank filters because their values are also split into
case-insensitive substring OR terms.

### Decision required

Choose one definition of equivalence:

1. **Normalized-definition equality:** Ignore case, order, whitespace, duplicates, blanks, and
   amount scale, but allow logically redundant substring edits to count as changes.
2. **Predicate equivalence:** Also eliminate substring-subsumed OR terms so logically identical
   predicates remain unchanged.

### Recommended resolution

Use normalized-definition equality unless retaining pins across redundant substring edits is an
explicit product requirement. It is simpler and auditable, but the plan must stop claiming complete
matching-semantic equivalence.

Replace phrases such as "effective matching semantics" with "normalized filter-definition
semantics," and add the redundant-substring example as an explicit boundary test or documented
non-goal.

If predicate equivalence is required, specify the subsumption algorithm and its relationship to
database case and collation behavior before implementation.

## SVV-003: Exact String Normalization Rules

**Status:** Open  
**Type:** Implementation ambiguity

### Ambiguity

The plan names canonical sets but does not completely define how raw values become canonical
values. A worker could trim or normalize fields in ways that disagree with the existing query
path.

### Decision required

Specify normalization independently for text-substring filters and currency exact-match filters.

### Recommended resolution

Document these rules in the plan and cover them in reconciler tests:

- Account IDs, bank names, and search text are flattened into whitespace-delimited OR terms.
- Null and blank collection elements are discarded.
- Text OR terms are compared case-insensitively and without regard to order or duplicates.
- Currency codes are compared as case-insensitive exact values.
- Nonblank currency values are not trimmed unless the repository-query behavior is changed in the
  same work. Under current behavior, `"USD"` and `" USD "` are different constraints.
- State whether case normalization intentionally mirrors the existing `String.toLowerCase()` call
  or uses `Locale.ROOT`. Do not allow the reconciler and JPA specification to use silently different
  rules.
- Canonical values are comparison-only. Persist and query using the original target values.

Also cover null elements inside sets, not only null or empty sets.

## SVV-004: Pins Override Unchanged Target Filters

**Status:** Open  
**Type:** Product behavior confirmation

### Ambiguity

The plan states this rule, but it is sufficiently surprising that an implementer or reviewer may
mistakenly apply the complete target criteria to source pins.

Example:

```text
Source criteria:     bank=Chase, type=DEBIT
Source pinned item:  bank=Wells Fargo, type=CREDIT
Target criteria:     bank=Chase, type=CREDIT
Changed filter:      type=CREDIT
Expected pin result: retained
```

The pin remains even though it violates the target's unchanged `bank=Chase` criterion. It
continues to override that unchanged filter and is checked only against the changed type filter.

### Decision required

Confirm whether this selective-filtering rule is the desired product behavior.

### Recommended resolution

If confirmed, add the example to the plan, canonical saved-view documentation, OpenAPI operation
description, reconciler/service tests, and completion criteria. Explicitly prohibit applying the
complete target criteria when selecting retained pins.

If not confirmed, the central formula and service query must change before implementation.

## SVV-005: Historical Pins and Exclusions Are Treated Differently

**Status:** Open  
**Type:** Product behavior confirmation

### Ambiguity

The target drops source pins that are soft-deleted, missing, foreign-owned, or rejected by changed
filters. In contrast, it copies every raw stored exclusion, including soft-deleted, missing, and
currently irrelevant IDs.

The plan explains why exclusions remain useful after future criteria broadening, but does not state
as directly why historical pins are intentionally discarded.

### Decision required

Confirm the asymmetry as part of the durable contract.

### Recommended resolution

Add an explicit rule:

> Variant creation preserves all source exclusion intent, but carries forward only active,
> owner-scoped source pins that satisfy changed target filters. Historical pin intent is not copied.

Document the behavior even if transaction restoration is not currently supported. Test both sides
of the asymmetry against raw persisted sets.

## SVV-006: Atomicity and Concurrent Source Edits

**Status:** Open  
**Type:** Transaction and concurrency semantics

### Ambiguity

The Phase 2 title calls the operation atomic. `@Transactional` makes target creation and override
copying succeed or fail together, but it does not guarantee that the source cannot change
concurrently. Under the normal PostgreSQL isolation level, no lock or version check provides a
stable source revision throughout the operation.

### Decision required

Decide whether transactional all-or-nothing creation is sufficient or whether the operation must
detect or serialize concurrent source edits.

### Recommended resolution

Keep the simple transactional behavior unless observed usage requires versioning. Rename the phase
to "Implement Transactional Variant Creation" and document:

> The target is created transactionally from the source state loaded by the operation. Concurrent
> source edits are not serialized or version-checked.

Do not use the term snapshot unless it is qualified as an application-level copy of the observed
definition and curation rather than a database snapshot-isolation guarantee.

## SVV-007: Documentation Is Deferred Beyond the Behavior Change

**Status:** Open  
**Type:** Repository-instruction violation

### Problem

Phase 2 introduces the complete business behavior, while canonical saved-view and domain
documentation is postponed until Phase 4. Repository instructions require affected documentation
to be updated in the same work and prohibit leaving documentation as follow-up work.

### Required resolution

Move documentation into the phase that introduces each change:

- Phase 1: Javadoc and tests should define internal reconciliation semantics; external behavior is
  not yet exposed.
- Service phase: update `docs/saved-views.md` and relevant `docs/domain-model.md` business rules.
- API phase: update `docs/api/README.md` and OpenAPI together with the endpoint.
- Final phase: audit and reconcile documentation, but do not introduce documentation that earlier
  phases should already have supplied.

No `AGENTS.md` update is needed unless implementation instructions, guardrails, or discovery
commands actually change.

## SVV-008: AI Session Handler Phases Are Oversized

**Status:** Open  
**Type:** Plan execution reliability

### Problem

Phase 4 contains eight substantial steps spanning PostgreSQL integration behavior, documentation,
code-quality auditing, stale-claim searches, and the full build. Phase 3 also spans controller
implementation, authorization and mapping tests, OpenAPI contract tests, and maintained API
documentation.

The AI Session Handler plan format recommends a fresh phase when a phase has more than about five
substantial steps or spans three or more major concerns.

### Recommended resolution

Use five focused phases:

1. Define and prove criteria reconciliation.
2. Implement transactional service behavior and update saved-view/domain documentation.
3. Expose the controller operation and prove HTTP behavior.
4. Prove generated OpenAPI and update the maintained API reference.
5. Prove PostgreSQL behavior, audit all documentation and changed code, and run final validation.

Reassess the final phase after moving behavioral documentation earlier. If it still contains more
than about five substantial steps, separate persisted integration coverage from final auditing.

Each phase must leave a coherent, focused-test-passing checkpoint for the next fresh session.

## SVV-009: Internal-Mocking Guidance Is Contradictory

**Status:** Open  
**Type:** Test strategy ambiguity

### Ambiguity

The referenced service-common testing guide states a hard prohibition on Mockito for internal
repositories, services, and controllers. The same guide later demonstrates Mockito for service
dependencies. This repository also already contains mocked `SavedViewServiceTest` and
`@WebMvcTest` controller coverage.

The plan explicitly asks workers to extend those mocked tests and to verify implementation details
such as one save, query skipping, and no downstream repository calls.

### Decision required

Choose whether this work follows the literal no-internal-mocks rule or the established local test
precedent.

### Recommended resolution

Record the decision in the plan before execution. Prefer behavior-oriented PostgreSQL integration
coverage for service behavior, retaining mocked controller coverage only where required by the
established authorization test structure. If mocked service tests remain, explain that they are
focused orchestration tests following the repository's existing precedent.

Do not let a phase worker discover and arbitrate this documentation contradiction during
implementation.

## SVV-010: Service-Common Recovery Conflicts With Workspace Boundaries

**Status:** Open  
**Type:** Prerequisite and execution-flow ambiguity

### Ambiguity

The plan says to stop and report when service-common cannot be resolved. Repository troubleshooting
instructions instead say to build and publish the sibling service-common repository to Maven Local
and then retry transaction-service.

An AI Session Handler phase may execute in exactly one declared repository workspace, so a
transaction-service phase cannot switch to `../service-common` to perform that recovery.

### Decision required

Define the recovery path that takes precedence during an automated plan run.

### Recommended resolution

Keep transaction-service phases workspace-pure:

1. Stop the current phase and report the resolution failure.
2. Run a dedicated prerequisite phase or separate command in `../service-common` to execute its
   build and `publishToMavenLocal`.
3. Resume or rerun the transaction-service phase.

Do not tell a phase both to remain in `.` and to switch repositories.

Static review found no feature-level service-common, permission-service, orchestration, database
migration, or web-repository prerequisite. This issue concerns artifact recovery only.

## SVV-011: Foreign-Owner Stored-Pin Coverage Is Missing

**Status:** Open  
**Type:** Integration coverage gap

### Problem

Phase 4 completion criteria require proof that foreign-owner source pins are not copied. The
execution steps cover a missing or foreign-owned source view and owner-scoped source lookup, but do
not explicitly place a foreign user's transaction ID in the authenticated user's raw source pin
set.

Those are different cases:

- A foreign-owned source view must return 404.
- An owned source view containing a historical or corrupted foreign transaction pin must be
  created successfully while omitting that pin from the target.

### Required resolution

Add an integration scenario that:

1. Persists a transaction for another owner.
2. Inserts that transaction ID into an owned source view's raw `pinnedIds` set.
3. Creates the target view from that source.
4. Reloads the target and proves the foreign ID was not copied.
5. Proves no arbitrary owner-scoped transaction was promoted to a pin.

Retain the separate foreign-source-view 404 test.

## SVV-012: Existing Open-Ended Database Documentation Is Inaccurate

**Status:** Open  
**Type:** Documentation defect

### Problem

`docs/database-schema.md` currently says `open_ended` allows a view to ignore the upper date bound.
Actual behavior resolves a missing `dateTo` to the current date, which excludes future-dated
transactions and advances with the current date.

The plan says to leave the database document unchanged if accurate, but it is not accurate.

### Required resolution

Update the column description to state:

> When `open_ended=true` and stored `criteria.dateTo` is absent, membership resolution uses the
> current date as the inclusive effective upper bound. An explicit stored `dateTo` takes
> precedence.

Make this correction in the same phase that first updates saved-view behavior documentation, then
audit it again during final verification.

## SVV-013: Query-Count Assertions Need a Defined Boundary

**Status:** Open  
**Type:** Performance and test assertion ambiguity

### Ambiguity

The service design calls for one owner-scoped source lookup, at most one retained-pin query, and one
target save. The HTTP controller then converts the saved target to `SavedViewResponse`, which calls
the existing membership resolver to compute active counts. That response mapping can issue an
additional criteria-membership query and an active override-ID query.

Without a stated boundary, "at most one changed-filter pin query" or "one target save path" could be
misread as a query budget for the entire HTTP request.

### Recommended resolution

Define two separate expectations:

- **Creation service:** one source lookup, zero or one retained-pin query, and one target save.
- **Complete HTTP request:** creation work plus the existing saved-view response-resolution queries.

Keep response-count optimization as a non-goal, but document that the request-body size is bounded
independently of transaction count while response count calculation may still query dynamic
membership.

Controller and service tests should assert only the query boundary owned by the component under
test.

## Final Implementation Gate

Implementation may begin when:

1. Every `SVV-###` issue above is checked or has a recorded disposition.
2. The chosen feature terminology and endpoint are applied throughout the plan.
3. The criteria-equivalence rule is precise enough to derive deterministic tests without worker
   interpretation.
4. Product owners have confirmed selective pin filtering and historical override asymmetry.
5. The plan's phases comply with documentation timing and workspace boundaries.
6. Required integration scenarios map directly to execution steps rather than appearing only in
   completion criteria.
