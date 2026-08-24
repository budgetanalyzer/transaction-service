# Static Saved Views and Display-Amount Contract Plan

Replace dynamic saved-view predicates with explicit transaction membership, make the browser's
selected-currency amount the single source for user-facing amount behavior, and document the
separate native-amount semantics of paged administrative search. This plan also makes the existing
complete self-scoped transaction response an intentional application contract: the browser holds
the full active collection, performs interactive filtering, sorting, and aggregates locally, and
only paginates the rendered table.

The target ownership model is:

| Concern | Authority |
| --- | --- |
| Active transaction truth, ownership, and soft deletion | Transaction Service |
| Static saved-view membership | Transaction Service |
| Complete current-user transaction snapshot | Transaction Service API and TanStack Query cache |
| Interactive search, filter, sort, aggregates, and presentation pagination | Web application |
| Dated USD-based rate facts and publication provenance | Currency Service |
| Displayed, filtered, sorted, and aggregated converted amount | One web display-amount model |
| Cross-user administrative filtering and paging | Transaction Service native-value search |

The saved-view API and persistence cutover are deliberately breaking. `POST /v1/views` accepts
`name` and `transactionIds`; an empty membership is valid. Membership is an unordered set. The
service canonicalizes duplicate IDs and rejects the complete create or add operation when any
requested addition is not an active transaction owned by the caller.
`PATCH /v1/views/{id}/transactions` applies disjoint `addTransactionIds` and
`removeTransactionIds` sets atomically, and removal is idempotent. Single and bulk transaction
soft deletion remove the affected membership rows in the same database transaction without
changing any saved view's `updated_at`. There is no criteria, `openEnded`, pin, exclusion, source
lineage, or full-membership replacement operation.
Successful membership deltas return `204 No Content`; clients refresh membership and metadata
caches rather than consuming a special delta-result model. Delta mutation limits the multi-writer
lost-update surface, so view revisions and `ETag`/`If-Match` are deferred until concurrent-edit
evidence warrants that protocol.

No existing saved view is migrated or interpreted. The cutover migration deletes every
`saved_view` row before dropping `criteria`, `open_ended`, `pinned_ids`, and `excluded_ids`, then
creates the minimal static membership table. Runtime code, tests, API models, and UI contain only
the new architecture; there is no old/new discriminator or compatibility branch. Historical
Flyway files remain immutable migration history, not supported runtime behavior.

The membership-table invariant is that every stored association points to an active transaction.
Create/add validation establishes it, and single/bulk transaction soft deletion removes affected
associations atomically. Both workflows lock affected transaction rows in deterministic ID order
so a concurrent add cannot commit after deletion cleanup. Membership ID and count queries therefore
read the association table directly rather than joining `transaction` on `deleted = false`. The
web still intersects member IDs with its active transaction snapshot to tolerate cache timing, not
to enforce deletion semantics. Transaction-driven cleanup does not change saved-view audit
timestamps.

The web display-amount result is a discriminated value: either an available amount quantized once
to the selected ISO currency's minor units, or an unavailable result with a reason. Conversion
uses the transaction date and the Currency Service's dense daily response; `publishedDate` remains
available as provenance. The UI must never relabel a native amount as a converted amount when a
rate is absent. Rendering, amount bounds, sorting, transaction totals, monthly aggregates, and
analytics all consume this same result. Amount sorting places unavailable values last and uses
date then transaction ID as deterministic tie-breakers. Totals omit unavailable conversions and
explicitly report that they are partial. An active amount range excludes unavailable conversions
after rate loading settles and visibly reports that exclusion; saving is disabled while the
required rate request is loading or failed. A settled view may be saved from the exact visible ID
set after the exclusion notice is shown.

Amount-filter URLs include `amountCurrency`. That value initializes the display currency when a
URL is opened. A later user-initiated display-currency change clears `minAmount`, `maxAmount`, and
`amountCurrency` with a notice rather than silently reinterpreting the same bounds in a different
unit.

The complete current-user snapshot is an intentional product contract, not a transport design that
this plan will revisit. Manual UI testing through 10,000 active transactions was tolerable; that is
an observed data point, not a hard limit, SLA, or request for an automated volume/performance test.
Establishing the all-transactions loading/synchronization position is documentation-only. Do not
change `GET /v1/transactions`, introduce transport pagination, cursor hydration, delta
synchronization, or virtualization, or plan such a migration as part of this work. Any future
reconsideration requires a new explicit product/architecture decision; none is currently planned.
Bounding Currency Service rate requests is separate display-amount work. Enforcing positive stored
transaction magnitudes is also an independent data migration and is not part of this plan.

Administrative `minAmount` and `maxAmount` compare the stored numeric amount regardless of the
row's currency. `currencyIsoCode` is already an independent exact search criterion. An amount-only
query is valid and spans currencies without normalization; supplying both currency and amount
criteria applies their conjunction and is the normal economically meaningful workflow. Native
amount sorting likewise compares stored numeric values. The administrative UI remains independent
of the global display-currency model and exposes currency as search criteria rather than a display
currency selector.

The backend saved-view cutover is one deliberately larger phase. The destructive migration,
domain/repository model, service behavior, HTTP contract, owner documentation, and affected tests
must land together so the phase ends with the mandatory full build passing. Do not split that phase
by staging old and new runtime models or by leaving a schema that the application cannot start
against. Repository changes remain confined to each phase's declared workspace.

## Phase 1: Atomically Cut Over the Static Saved-View Backend

### Workspace

.

### Goal

Replace the complete dynamic saved-view backend with one build-valid static-membership
implementation and no compatibility path.

### Scope

Perform the destructive V22 schema change, replace the persistence/domain/service/API models,
delete legacy code and tests, publish the breaking static routes, update the saved-view owner
documents, and complete focused plus full backend validation in the same phase.

### Non-goals

Frontend behavior; preserving or reconstructing any existing view; rewriting historical Flyway
files; criteria, open-ended, pin, or exclusion compatibility; membership order, provenance, or
timestamps; full-set replacement; cross-user views; or optimistic HTTP revisions.

### Required context

Read `AGENTS.md`, `docs/api/README.md`, `docs/database-schema.md`, `docs/domain-model.md`,
`docs/saved-views.md`, the Permission Service authorization model, the full ordered migration
history, all saved-view domain/repository/service/controller/API models and tests, and the shared
Spring conventions, code quality, error handling, and testing pattern documents required by
`AGENTS.md`.

### Execution steps

1. Add `V22__replace_saved_views_with_static_membership.sql`. Make its first executable statement
   `DELETE FROM saved_view;`, drop `criteria`, `open_ended`, `pinned_ids`, and `excluded_ids`, and
   create `saved_view_transaction` with only non-null `view_id` and `transaction_id`, a composite
   primary key, `ON DELETE CASCADE` from the view, and a normal transaction FK. Do not backfill or
   add a timestamp, order, membership type, provenance, or legacy payload. Add an index beginning
   with `transaction_id` for transaction soft-delete cleanup.
2. Reduce `SavedView` to UUID, owner, name, and audit timestamps. Add a scalar composite-key
   membership model/repository without a `SavedView` collection, with batch insertion, idempotent
   bulk removal, deterministic member-ID reads, grouped counts, and set-based deletion by one or
   many transaction IDs. Add active-owner transaction lookup operations that lock sorted unique
   transaction rows for create/add validation and deletion. Membership reads and counts operate
   directly on the association table; they must not join `transaction` merely to filter deleted
   rows.
3. Replace `SavedViewService` and its DTOs with atomic static create, name update, membership read,
   count, add, and remove behavior. Canonicalize duplicate IDs; allow empty create; validate every
   addition as active and owner-scoped before writing anything; make unknown removals a no-op;
   reject overlapping add/remove sets; touch view `updated_at` on explicit membership deltas; and
   use `422 APPLICATION_ERROR` code `SAVED_VIEW_MEMBERSHIP_STALE` without disclosing inaccessible
   IDs. Update single and bulk `TransactionService` soft-delete paths to lock successfully resolved
   transaction rows in the same deterministic order used by create/add, mark them deleted, and
   delete their memberships in the same transaction without touching view timestamps.
4. Replace the HTTP contract: create with `{ "name", "transactionIds" }`; list/get metadata with
   active count; `PATCH /v1/views/{id}` for name only; active membership
   `{ "transactionIds" }`; `PATCH /v1/views/{id}/transactions` with disjoint add/remove arrays and
   `204 No Content`; and the existing delete route. Delete `PUT`, every pin/exclude route, all
   legacy request/response/service/domain models and converters, and their old-only tests. Preserve
   fine-grained `views:read`, `views:write`, and `views:delete` authorization and actor identity from
   `SecurityContextUtil`.
5. Add focused migration, repository, service, controller, authorization, validation, error, and
   OpenAPI tests for the final architecture, including destructive row deletion, exact schema,
   cascades, the reverse cleanup index, empty/duplicate/10,000-ID create, owner rules, transactional
   membership cleanup for single and bulk soft delete, a concurrent add-versus-delete case proving
   no dangling membership can commit, unchanged view timestamps during cleanup, idempotent removal,
   grouped counts, `204` deltas, removed routes, and absence of legacy schemas.
   Rewrite `docs/saved-views.md` and update `docs/api/README.md`, `docs/domain-model.md`, and
   `docs/database-schema.md` in the same phase.

### Implementation notes

Keep the migration transactional so failed DDL rolls back the delete. Historical V4 and V16 remain
unchanged for Flyway checksum integrity and clean database construction; they are not runtime
compatibility. Avoid eager/lazy membership collections and legacy/new discriminators. The
10,000-ID case verifies correctness rather than timing. Log only counts and view IDs, never
financial contents or inaccessible requested IDs. Let `TransactionService` use the membership
repository directly for deletion cleanup; do not introduce a `TransactionService` ->
`SavedViewService` dependency or an event whose eventual consistency would weaken the invariant.
Acquire transaction locks in sorted unique ID order on both membership-add and soft-delete paths to
avoid deadlocks and close the validation-to-insert race. Use real transactional integration tests
for the race; do not mock application services or repositories.
This phase is intentionally larger than normal because no smaller destructive checkpoint can
satisfy both the clean-cutover requirement and the repository's mandatory full-build gate.

### Validation

Run focused suites during iteration, including the schema migration, repository, service,
controller, authorization, and OpenAPI tests. Then run:

```bash
./gradlew clean spotlessApply
./gradlew clean build
git diff --check -- AGENTS.md README.md docs src
rg -n 'openEnded|pinnedIds|excludedIds|ViewCriteria|/pin|/exclude' \
  src/main/java src/test docs/api docs/saved-views.md docs/domain-model.md docs/database-schema.md
```

Inspect the complete build output for Checkstyle warnings and review every final search match as
intentional historical text or remove it.

### Completion criteria

The latest schema, Java runtime, API, tests, and owner docs describe only static membership; V22
deletes every old view; no legacy column, route, model, converter, test fixture, compatibility
branch, or unused membership field remains; and the mandatory full backend build passes.

## Phase 2: Harden Static-Membership Persistence

### Workspace

.

### Goal

Characterize and harden the final static-membership repository path after the atomic cutover.

### Scope

Review repository query shape, batch behavior, counts, transaction-delete cleanup, and relational
constraints under realistic membership sizes; fix only issues found in the new architecture.

### Non-goals

Restoring any legacy model, adding additional schema fields or indexes without a demonstrated
query, changing HTTP routes, frontend work, transaction restore behavior, or adding timing-based
performance gates.

### Required context

Re-read `AGENTS.md`, `docs/domain-model.md`, `docs/database-schema.md`, V22 and its migration test,
the final saved-view membership repositories, `TransactionRepository`, and the shared Java
architecture, quality, and testing documents required by `AGENTS.md`.

### Execution steps

1. Inspect the final association mapping and queries for accidental `Transaction` hydration,
   `SavedView` collections, per-view count queries, or repository/API layering crossings. Remove
   any such issue without adding alternate legacy paths.
2. Extend repository and service integration tests for deterministic member IDs, grouped counts,
   duplicate membership rejection, view-delete cascade, transaction FK restriction, and set-based
   membership cleanup during both single and bulk transaction soft deletion. Assert affected view
   timestamps do not change. Add a real concurrent add-versus-delete test proving the row-locking
   protocol leaves either an active member or a deleted transaction with no membership, never a
   deleted transaction with a dangling membership.
3. Characterize a 10,000-ID create through the final repository path. If the current JPA batching
   is incorrect or exceeds a concrete database/driver limit, make the smallest batching fix and
   keep transactionality; do not add a wall-clock assertion.
4. Verify explicit membership removal is idempotent, transaction-driven cleanup uses the
   `transaction_id` index, and list/count queries use the association table without a deleted-state
   join. Verify all multi-ID lock acquisition uses sorted unique IDs. Add another index only if an
   actual final query shape demonstrates the need.
5. Update `docs/database-schema.md` or `docs/domain-model.md` if hardening changes the documented
   final design, then run focused tests and the mandatory formatting/full-build sequence.

### Implementation notes

Keep controllers free of persistence details and do not expose the association entity through API
models. The absence of `added_at` and a view-side collection is intentional. The reverse
`transaction_id` index exists specifically for single/bulk soft-delete cleanup.

### Validation

Run focused repository and migration tests, including
`./gradlew test --tests '*SavedView*Repository*' --tests '*SavedViewSchemaMigrationTest'`.
Then run `./gradlew clean spotlessApply` and `./gradlew clean build`; inspect all output for
Checkstyle warnings.

### Completion criteria

The final static repository path is batch-correct at the characterized size, avoids accidental
entity hydration, N+1 counts, and deleted-state joins, cleans membership transactionally on soft
delete without changing view timestamps, prevents concurrent add/delete races, and passes the full
build without speculative schema.

## Phase 3: Harden Atomic Membership Semantics

### Workspace

.

### Goal

Audit and harden owner-scoped create, read, count, add, and remove semantics over the clean static
model.

### Scope

Exercise all-or-nothing validation, idempotency, stale-snapshot handling, count consistency, and
transaction boundaries through focused service integration tests; fix only final-model defects.

### Non-goals

Changing controller routes or API models, reintroducing legacy concepts, adding full-set
replacement, source-view lineage, ordering, optimistic HTTP revisions, or cross-user views.

### Required context

Read `AGENTS.md`, the shared Spring conventions, code quality, error handling, and testing pattern
documents, then inspect the final `SavedViewService`, membership repositories, error contract, and
service/integration tests. Preserve authentication-owner boundaries supplied by the controller.

### Execution steps

1. Verify create canonicalizes duplicate IDs, permits an empty set, and writes neither metadata nor
   partial membership when any unique ID fails the active-owner check. Verify validation holds the
   required transaction-row locks through membership insertion.
2. Verify membership delta rejects overlap before persistence, validates the complete add set,
   treats already-present additions and unknown removals idempotently, and rolls back additions and
   removals together on failure.
3. Verify wrong-owner, missing, and soft-deleted additions all produce the same non-leaking
   `SAVED_VIEW_MEMBERSHIP_STALE` application error, while explicit removal operates only under an
   owner-checked view and remains idempotent after transaction-driven cleanup.
4. Verify explicit membership deltas touch the view timestamp only when the set changes, while
   transaction soft-delete cleanup never touches view timestamps. Verify list/get counts use one
   grouped association query rather than a query per view or a deleted-state join.
5. Delete any obsolete service fixture discovered during the audit, update owner documentation if
   a final semantic detail changes, and run focused plus mandatory full validation.

### Implementation notes

Use the shared `BusinessException` path to return `422 APPLICATION_ERROR` with code
`SAVED_VIEW_MEMBERSHIP_STALE` for a syntactically valid create/add request containing any ID that
is not an active transaction owned by the caller. Use `400 Bad Request` for an invalid delta shape.
Do not return which inaccessible IDs belong to other users. Explicit membership removal only
addresses rows under the already owner-checked view and therefore does not need to load a
transaction. Transaction-driven cleanup deletes associations by successfully soft-deleted IDs in
the same transaction, uses the membership repository directly, and does not mutate `SavedView`.
Both workflows acquire transaction locks in sorted unique ID order. Keep batch logs to counts and
view IDs; never log financial row contents.

### Validation

Run `./gradlew test --tests '*SavedViewServiceTest' --tests '*SavedViewServiceIntegrationTest'` and
any focused repository tests changed in this phase. Then run `./gradlew clean spotlessApply` and
`./gradlew clean build`; inspect all output and fix every Checkstyle warning.

### Completion criteria

The service layer has only static membership semantics, additions and deltas are owner-scoped and
atomic, removals are idempotent, stale errors do not leak ownership, counts are consistent, and the
full build passes.

## Phase 4: Lock the Breaking Static Saved-View API

### Workspace

.

### Goal

Lock the final static collection contract through exhaustive validated, authorized HTTP and
runtime OpenAPI coverage.

### Scope

Audit the final request/response models and routes, close validation or authorization gaps, and
harden controller authorization and OpenAPI integration tests without changing the chosen
contract.

### Non-goals

Backward-compatible dynamic endpoints, transport pagination, a source-view query parameter,
membership ordering, `ETag` support, or frontend implementation.

### Required context

Read `AGENTS.md`, `docs/api/README.md`, the Permission Service authorization model named there,
shared error/testing documentation, `SavedViewController`, all saved-view API records, controller
authorization tests, and `TransactionOpenApiIntegrationTest`.

### Execution steps

1. Verify `POST /v1/views` accepts `{ "name": string, "transactionIds": number[] }`, permits an
   empty list, validates name and IDs, and returns `201`, `Location`, and exact metadata with active
   transaction count.
2. Verify `PATCH /v1/views/{id}` is name-only and `GET /v1/views/{id}/transactions` returns sorted
   active `{ "transactionIds": number[] }`. Assert `PUT /v1/views/{id}` and all dynamic fields are
   absent rather than deprecated.
3. Verify `PATCH /v1/views/{id}/transactions` validates non-null, positive, disjoint add/remove
   arrays with at least one nonempty set; returns `204 No Content`; and exposes no delta response or
   full-membership replacement model.
4. Audit every saved-view endpoint for `views:read`, `views:write`, or `views:delete` method
   security, 404 owner isolation, and actor identity exclusively from `SecurityContextUtil`. Assert
   every single/bulk pin/exclude route and record is absent.
5. Expand controller, authorization, validation, error-response, method-not-allowed, and OpenAPI
   tests for the exact breaking contract and `SAVED_VIEW_MEMBERSHIP_STALE`; delete any remaining
   legacy-only fixture; then run focused and full backend validation.

### Implementation notes

Do not place user identity in request bodies. The membership endpoint returns IDs rather than
transaction objects because the browser already owns the complete self-scoped snapshot. Keep
`PATCH` atomic and idempotent for repeated identical deltas. Document
`SAVED_VIEW_MEMBERSHIP_STALE` for the web client and do not add a service-local HTTP exception or
exception handler.

### Validation

Run focused controller, authorization, and OpenAPI suites, including
`./gradlew test --tests '*SavedViewController*' --tests '*TransactionOpenApiIntegrationTest'`.
Inspect the generated OpenAPI JSON assertions for removed pin/exclude paths and exact create/delta
schemas. Then run `./gradlew clean spotlessApply` and `./gradlew clean build`; inspect all output
for Checkstyle warnings.

### Completion criteria

The runtime contract exposes only static membership, every application endpoint remains
fine-grained-authorized, old dynamic routes are absent, focused HTTP/OpenAPI tests pass, and the
full build remains clean.

## Phase 5: Document the Self Snapshot and Lock Native Admin Contracts

### Workspace

.

### Goal

Finish the Transaction Service rollout by documenting the unchanged self-scoped full snapshot,
clarifying native-value administrative search, and completing the static saved-view cleanup and
validation.

### Scope

Document the existing unpaged self endpoint without changing or adding tests for it, clarify
administrative amount semantics without changing them, remove obsolete dynamic-view code/docs, and
run the complete backend validation sequence required by the runtime changes in earlier phases.

### Non-goals

Changing or adding snapshot-specific tests for `GET /v1/transactions`; paginating it; planning a
cursor/delta synchronization migration; changing administrative search behavior; converting
amounts on the backend; adding aggregate endpoints; changing NGINX; or enforcing positive stored
amounts.

### Required context

Read `README.md`, `docs/api/README.md`, `docs/saved-views.md`, `docs/domain-model.md`,
`docs/database-schema.md`, the transaction controller and administrative search tests, and
`docs/issues/transaction-filter-contract-divergence-systemic-design-defect.md`. Preserve unrelated
work already present in issue documents.

### Execution steps

1. Update the API owner documentation to state that `GET /v1/transactions` intentionally returns
   the complete active collection for the authenticated owner as a plain array. Record that the
   browser filters, sorts, and aggregates that collection locally while table pagination is only
   presentation. Do not change the endpoint or add snapshot-specific regression, volume, or
   performance tests. Keep `/v1/transactions/search` and `/search/count` documented as paged,
   permission-gated cross-user administration.
2. Clarify controller/OpenAPI parameter and sort descriptions: administrative `minAmount`,
   `maxAmount`, and `sort=amount,...` compare each row's stored numeric value without currency
   normalization. An amount-only query is valid across all currencies. `currencyIsoCode` is an
   independent exact criterion, and combining it with amount bounds is the normal way to make the
   numeric comparison currency-specific.
3. Extend administrative specification/controller tests to prove amount-only search and count can
   return numerically matching rows in different currencies, currency-only filtering still works,
   currency plus amount uses conjunction, and amount sorting remains raw numeric ordering. These
   are contract tests for existing behavior, not a backend behavior change.
4. Verify repository-wide that saved-view-only `ViewCriteriaApi`, `ViewCriteria`, converters,
   service DTOs, `TransactionCriteria.fromViewCriteria`, and their test cases are gone; delete any
   remainder rather than retaining dead conversion fixtures. Keep JPA Specifications and
   `TransactionFilter` for advanced administrative search.
5. Rewrite `docs/saved-views.md` as the static collection owner document and update
   `docs/api/README.md`, `docs/domain-model.md`, and `docs/database-schema.md`. Add a resolution note
   to the divergence issue that preserves its diagnosis but supersedes its dynamic clause design.
   Remove the obsolete `docs/plans/saved-view-save-as.md` and mark the related source-plan review as
   superseded rather than rewriting it as current architecture.
6. Run the mandatory backend formatting and full-build sequence and inspect all output for
   Checkstyle warnings. Fix documentation links and examples and confirm no service/repository
   import of a removed saved-view API model remains.

### Implementation notes

Describe browser ownership as a product architecture decision, not an accidental missing page
parameter. Distinguish transport completeness from visual pagination. Record 10,000 only as the
manually observed test point; do not create a hard maximum, performance SLA, or automated loading
envelope from it, and do not imply that memoization removes network, JSON parse, memory, or
rate-series costs. The old issue remains useful history; its proposed shared dynamic predicate
contract is no longer the implementation direction.

### Validation

Run:

```bash
./gradlew clean spotlessApply
./gradlew clean build
git diff --check -- AGENTS.md README.md docs src
rg -n 'openEnded|pinnedIds|excludedIds|ViewCriteria|/pin|/exclude' \
  src/main/java src/test docs/api docs/saved-views.md docs/domain-model.md docs/database-schema.md
```

The final `rg` should return only intentionally retained historical/superseded references; review
each match.

### Completion criteria

The Transaction Service is fully build-clean on the static model, the unchanged complete self
snapshot is explicit in owner docs, native admin amount semantics are locked by relevant tests and
documentation, and obsolete executable dynamic-view guidance is removed.

## Phase 6: Build the Display-Amount Primitive and Rate Input Contract

### Workspace

../budget-analyzer-web

### Goal

Create one pure frontend display-amount model that cannot silently confuse native and converted
numeric values.

### Scope

Add the discriminated conversion result, selected-currency quantization, exact dated rate lookup,
publication provenance, and bounded rate loading based on the complete transaction snapshot.

### Non-goals

Changing table filters or statistics yet, changing Currency Service, adding a rate batch endpoint,
or introducing a decimal library without evidence that the existing numeric domain requires it.

### Required context

Read `AGENTS.md`, `docs/api-integration.md`, `docs/state-architecture.md`,
`docs/testing-guide.md`, `docs/react-hooks-lifecycle-mental-model.md`, the generated currency API
schema, `src/hooks/useCurrencies.ts`, `src/types/currency.ts`, and `src/utils/currency.ts`.

### Execution steps

1. Extend `ExchangeRateResponse` with the existing Currency Service `publishedDate` field and keep
   the response `date` as the effective transaction date. Do not synthesize publication dates in
   the browser.
2. Add one pure display-amount API returning either an available selected-currency value with
   currency, minor-unit precision, quantized value, and used-rate provenance, or an unavailable
   result with an explicit reason. Same-currency values are available without a rate.
3. Convert USD-to-target, source-to-USD, and non-USD triangulation using exact dense-series entries
   for the transaction date. Remove nearest/earliest and native-number fallback behavior from the
   new contract; a missing required rate is unavailable.
4. Derive the exchange-rate request range from the earliest and latest dates in the complete
   transaction snapshot rather than always starting at `2000-01-01`. Preserve TanStack Query
   caching and parallel per-currency requests, and expose loading/error state needed by consumers.
5. Add pure utility and hook tests for same-currency, USD legs, cross-currency triangulation,
   weekend/holiday `publishedDate`, ISO minor units including zero- and three-decimal currencies,
   missing rates, date-range derivation, and no raw fallback.

### Implementation notes

Quantize once and make consumers compare the exact quantized value the user sees. Keep transaction
direction separate: the display amount is a magnitude and `TransactionType` determines debit or
credit treatment. The existing stored-amount positivity inconsistency is not repaired here; use a
single documented magnitude normalization at the display boundary until that separate migration
is designed.

### Validation

Format the changed files, run the focused currency utility and hook tests with `npx vitest run`, and
run `npm run lint:fix`. Confirm no changed hook introduces render-time state synchronization or an
unstable query-key dependency.

### Completion criteria

The web has a tested display-amount primitive with explicit unavailable behavior and provenance,
and rate loading is bounded by the complete transaction snapshot's actual date range.

## Phase 7: Unify Transaction Filtering, Sorting, and Statistics

### Workspace

../budget-analyzer-web

### Goal

Make the main transaction experience use the selected display amount for every amount-dependent
decision while retaining full in-browser computation and presentation pagination.

### Scope

Update URL filter state, the transaction page, table, amount badge, filter utility, and statistics
to share one memoized display-amount result per transaction.

### Non-goals

Static saved-view API integration, view-detail/analytics conversion, backend filtering, server
aggregates, DOM virtualization, or wall-clock performance gates.

### Required context

Read the web owner docs named in Phase 6, then inspect `TransactionsPage`, `TransactionTable`,
`TransactionAmountBadge`, `useTransactionFiltersSync`, `transactionFilters`,
`useTransactionStats`, `CurrencySelector`, and their tests.

### Execution steps

1. Build a memoized transaction-ID-to-display-amount projection once in `TransactionsPage` and
   pass or select it consistently; remove `amountInUsd` as the table sort authority.
2. Apply `minAmount` and `maxAmount` to available quantized selected-currency magnitudes. While
   required rates load, hold amount-dependent UI in a loading state. After settlement, exclude
   unavailable conversions from an active amount range and show the excluded count and reason.
3. Sort amount columns by the same available quantized value, put unavailable values last in both
   directions, and use date then ID as stable tie-breakers. Render an explicit unavailable state
   alongside the original native currency instead of relabeling the native number.
4. Calculate credits, debits, net balance, and monthly amount averages from the same available
   values. Keep transaction counts based on the visible rows, omit unavailable converted amounts
   from monetary totals, and mark affected cards as partial with the unavailable count.
5. Add `amountCurrency` to URL parsing/serialization. Make it initialize the selected display
   currency for deep links; on a later selector change, clear both amount bounds and their currency
   and show a concise notice.
6. Add focused correctness tests for filtering, stable sorting, counts, aggregates, loading,
   unavailable conversion, partial totals, deep-link initialization, and currency-change clearing.
   Do not add a transaction-snapshot volume or performance test for the documentation-only loading
   decision.

### Implementation notes

Keep TanStack Table's client-side pagination. The projection and filtered list should be memoized
from the canonical transaction array, selected currency, and rate map; do not copy them into Redux
or issue server searches. A display-currency switch may legitimately reorder transactions because
transaction-date target rates vary by date. This phase changes amount semantics over the existing
snapshot; it does not change how transactions are transported or synchronized.

### Validation

Run focused filter, URL-sync, stats, transaction-table, amount-badge, selector, and transaction-page
tests with `npx vitest run`. Run the formatter on changed files and `npm run lint:fix`.

### Completion criteria

The main page displays, filters, sorts, and aggregates one selected-currency value, missing rates
are visible rather than silently numeric, and focused semantic tests pass without adding a loading
envelope test.

## Phase 8: Migrate Every Remaining Display-Amount Consumer

### Workspace

../budget-analyzer-web

### Goal

Eliminate alternate conversion semantics from view details, analytics, transaction details, and
confirmation UI.

### Scope

Move every remaining `convertCurrency` or ad hoc `amountInUsd` consumer to the common
display-amount model and delete obsolete fallback utilities once unused.

### Non-goals

Changing analytics product formulas unrelated to currency, static view membership, or adding new
Currency Service behavior.

### Required context

Read `AGENTS.md` and the web architecture/testing/hook owner docs, then search all source and tests
for `convertCurrency`, `findNearestExchangeRate`, `amountInUsd`, direct exchange-rate arithmetic,
and selected-currency totals.

### Execution steps

1. Update `ViewPage` and `ViewTransactionTable` amount rendering, filtering, sorting, and statistics
   to consume the common projection and missing-rate state.
2. Update analytics selectors/hooks and charts to aggregate only available display amounts and
   expose partial/unavailable status rather than incorporating native fallbacks.
3. Update transaction detail, edit/delete confirmation, and any remaining amount badges or modal
   summaries to use the same result and show native amount plus conversion-unavailable state where
   appropriate.
4. Delete or narrow the legacy numeric `convertCurrency` and nearest-rate helpers so production
   code cannot accidentally return a native number labeled as the target currency.
5. Rewrite focused tests for each migrated surface and add a repository search assertion or lintable
   convention proving no production `amountInUsd` or raw fallback call remains.

### Implementation notes

Do not make every component recompute the full map. Page-level or shared memoized selectors should
provide the same per-transaction result to tables and aggregates. Preserve original native amounts
for disclosure and debugging, but never use them as converted totals.

### Validation

Run focused view-table, view-page, analytics, transaction-detail, delete-modal, and currency utility
tests. Format changed files, run `npm run lint:fix`, and run:

```bash
rg -n 'amountInUsd|findNearestExchangeRate|convertCurrency' src --glob '*.{ts,tsx}'
```

Review every remaining match as an intentional declaration/test or remove it.

### Completion criteria

Every user-facing selected-currency amount flows through one discriminated contract, all affected
focused tests pass, and no production fallback can relabel an unconverted number.

## Phase 9: Replace the Saved-View Client Data Model

### Workspace

../budget-analyzer-web

### Goal

Align TypeScript API models, adapters, and TanStack Query hooks with static membership before
rewiring view UI workflows.

### Scope

Replace dynamic saved-view types and endpoints, implement create/name/delta mutations, and resolve
membership by intersecting IDs with the complete transaction cache.

### Non-goals

Create/clone button changes, add/remove user experience, temporary compatibility with old backend
responses, or fetching missing members individually.

### Required context

Read `AGENTS.md`, `docs/api-integration.md`, `docs/state-architecture.md`, the saved-view portion of
the generated API, `src/api/viewApi.ts`, `src/hooks/useViews.ts`, `src/types/view.ts`, transaction
query keys, mocks, and their tests.

### Execution steps

1. Replace criteria/open-ended/pin/exclusion types with static metadata, create `{name,
   transactionIds}`, membership `{transactionIds}`, name update, and membership delta request types
   matching the backend OpenAPI. Do not model a body for the `204` delta response.
2. Update `viewApi` for create, membership read, `PATCH` name update, and
   `PATCH /v1/views/{id}/transactions`; remove every pin/exclude adapter method.
3. Refactor view hooks to wait for the canonical `['transactions']` snapshot, build one ID map, and
   intersect active membership locally. Delete the per-ID missing-member request fan-out; a missing
   ID represents transient cache skew between independently fetched server resources and is not a
   fetch trigger. Persisted membership is cleaned when a transaction is soft-deleted.
4. Implement mutation cache handling that invalidates or updates view metadata and membership
   together after a successful atomic operation, and refreshes both plus the transaction snapshot
   after `SAVED_VIEW_MEMBERSHIP_STALE`.
5. Rewrite API, hook, MSW handler, and query-cache tests for empty/large membership, deterministic
   intersections, stale IDs, atomic error handling, and zero individual transaction fetches.

### Implementation notes

Do not copy server arrays into Redux or component state. The membership response and complete
transaction snapshot remain independently cached server state; derive transaction objects through
memoization. The web and backend are intentionally released together because the contract is
breaking.

### Validation

Run focused `viewApi`, `useViews`, transaction-cache, and mock-handler tests. Format changed files
and run `npm run lint:fix`.

### Completion criteria

The client data layer contains no dynamic membership concepts, all view transaction objects come
from the complete snapshot, and tests prove there is no N-per-ID fetch path.

## Phase 10: Save and Clone Exact Visible ID Sets

### Workspace

../budget-analyzer-web

### Goal

Make “Save as view” and clone workflows persist exactly the transaction IDs currently visible in
the browser.

### Scope

Rewrite create/save components on the main transaction page and view detail page, including
amount-rate readiness and empty collections.

### Non-goals

Editing membership after creation, source-view lineage, persisting filter criteria, or preserving
sort order as membership order.

### Required context

Read `AGENTS.md`, owner docs for state/API/tests/hooks, `TransactionsPage`, `ViewPage`,
`SaveAsViewButton`, `CreateViewModal`, the display-amount state from Phases 6-8, and their tests.

### Execution steps

1. Change `SaveAsViewButton` and `CreateViewModal` to accept a transaction ID collection and a name;
   remove criteria serialization and the open-ended control.
2. On `TransactionsPage`, submit `filteredTransactions.map(transaction => transaction.id)` after
   all active filters, including selected-currency amount bounds, have resolved. Permit an empty
   result and present it as an empty collection rather than an invalid filter.
3. Disable save while an active amount filter's required rates are loading or failed. Once loading
   settles, allow saving the exact visible IDs while keeping the explicit unavailable-conversion
   exclusion notice on screen.
4. Add Save As/Clone to `ViewPage`; submit the currently visible filtered member IDs. Do not send a
   source view ID, criteria, pins, exclusions, or ordering metadata.
5. Rewrite modal, button, main-page, and view-page tests for unfiltered save, filtered save, empty
   save, amount readiness, unavailable exclusions, clone independence, and large ID request shape.

### Implementation notes

The server revalidates active ownership because the browser snapshot can become stale between
render and submit. On `422 SAVED_VIEW_MEMBERSHIP_STALE`, refresh and explain that the visible set
changed; do not retry with a silently reduced ID list.

### Validation

Run focused save/modal/transactions-page/view-page tests, format changed files, and run
`npm run lint:fix`.

### Completion criteria

Every creation path submits only name plus the exact visible ID set, stale snapshots never become
partial collections, and clone/save behavior is independent of its source.

## Phase 11: Replace Pin and Exclusion UI with Remove Membership

### Workspace

../budget-analyzer-web

### Goal

Make saved-view detail editing operate on one membership set and remove all matched/pinned/excluded
presentation concepts.

### Scope

Simplify view cards/detail/settings, implement bulk removal, and adapt transfer/refund-assisted
actions that currently exclude candidates.

### Non-goals

Adding transactions from outside the view, restoring deleted transactions, reordering members, or
changing transfer/refund discovery logic.

### Required context

Read the web owner docs and inspect `ViewPage`, `ViewTransactionTable`, `ViewCard`,
`ViewCriteriaSummary`, `ViewSettingsMenu`, `EditViewModal`,
`RestoreExcludedTransactionsModal`, transfer/refund actions, permissions, and all related tests.

### Execution steps

1. Remove criteria summaries, open-ended badges/toggles, membership-type labels, pinned/excluded
   counts, pin/unpin actions, exclusion actions, and the restore-excluded modal. Make edit/settings
   name-only.
2. Add owner-visible bulk “Remove from view” behavior that sends selected member IDs in one atomic
   delta and updates the membership/count caches on success.
3. Replace transfer/refund workflows that used exclusion as a hide operation with explicit member
   removal while preserving their existing discovery and confirmation behavior.
4. Keep transaction soft delete as a separate transaction permission/action. A soft-deleted
   member disappears because the backend transactionally removes its membership and the complete
   active snapshot omits the transaction, not because the UI sends a membership delta.
5. Rewrite view card, settings, table, detail, restore-removal, and transfer/refund tests and remove
   dead components/types after repository-wide reference checks.

### Implementation notes

Use “member” and “remove” consistently. Do not retain pin/exclude terminology as hidden
implementation detail. Removal should be available to `views:write` users independently of
transaction-delete permission.

### Validation

Run focused view component/page and transfer/refund tests, format changed files, run
`npm run lint:fix`, and inspect:

```bash
rg -n 'openEnded|pinned|excluded|pinTransaction|excludeTransaction|ViewCriteria' \
  src --glob '*.{ts,tsx}'
```

### Completion criteria

The view UI presents one static membership set, removal works in bulk, soft deletion remains
separate, and no production pin/exclusion/open-ended concept remains.

## Phase 12: Add Transactions to Existing Views

### Workspace

../budget-analyzer-web

### Goal

Complete the playlist-like curation model with a discoverable way to add transactions from the
already-loaded full snapshot.

### Scope

Add an “Add transactions” navigation/selection mode that reuses the main transaction filters and
table, excludes existing members from selection, and submits one atomic add delta.

### Non-goals

Building a second full-snapshot picker, server-side search, adding soft-deleted transactions,
membership ordering, or partial success.

### Required context

Read `AGENTS.md`, state/API/testing/hook docs, main table row-selection permissions, view selector,
navigation URL conventions, `ViewPage`, `TransactionsPage`, and the static membership hooks from
Phase 9.

### Execution steps

1. Add an “Add transactions” action on `ViewPage` that navigates to the main transaction experience
   with a URL-owned target view ID and return location. Reuse the existing complete snapshot,
   filters, selected display currency, and presentation pagination.
2. Decouple table row selection from transaction-delete permission. In add mode, gate selection and
   submission on `views:write`, identify existing members from the membership cache, and disable or
   clearly mark rows already in the target view.
3. Submit selected IDs as one `addTransactionIds` delta, keep the user in place on validation or
   stale-membership errors, and return to the view after success with refreshed membership and
   counts.
4. Implement cancel and success cleanup for the target-view and return-location parameters so add
   mode cannot leak into later ordinary transaction navigation.
5. Add permission, URL/deep-link, existing-member, filtered selection, success, stale-snapshot,
   empty-selection, cancellation, and return-navigation tests.

### Implementation notes

The required path is View Page -> Add transactions -> shared Transactions table -> return. A
normal-page “add to view” chooser can be considered later if the required flow proves awkward; it
is not part of this plan.

### Validation

Run focused transaction-table selection, transactions-page, view-page, navigation, and permission
tests. Format changed files and run `npm run lint:fix`.

### Completion criteria

Users can add active transactions to a static view from the complete local snapshot, existing
members cannot be accidentally duplicated, and selection permissions are independent of delete
permissions.

## Phase 13: Clarify Native Administrative Amounts in the Web

### Workspace

../budget-analyzer-web

### Goal

Make paged administrative search use the backend's raw amount and independent currency criteria,
and document the complete-snapshot frontend architecture.

### Scope

Expose currency as administrative search criteria, explain raw mixed-currency amount semantics,
keep display-currency controls out of the admin surface, revise web architecture/API/state
documentation, and run focused UI validation before the generated API snapshot is refreshed.

### Non-goals

Client-side conversion of administrative results, loading every user's transactions, backend
behavior changes, or final full build against a stale generated OpenAPI file.

### Required context

Read `AGENTS.md`, `docs/api-integration.md`, `docs/state-architecture.md`, `docs/architecture.md`,
`docs/testing-guide.md`, the admin transaction search page/filter/table/URL modules, and the current
runtime OpenAPI descriptions from Phase 5.

### Execution steps

1. Keep the admin amount column and range controls tied to each row's stored amount and displayed
   ISO currency. Add concise help that bounds and sorting compare raw numeric values without FX
   normalization when the result contains multiple currencies.
2. Expose the already-supported `currencyIsoCode` criterion in the filter panel and retain its
   existing URL/request state. Place it with amount criteria so selecting currency plus an amount
   range is the normal obvious workflow.
3. Keep amount-only search valid and unblocked across currencies. Do not require a currency, clear
   amount bounds when currency changes, or add conditional warnings and validation branches.
4. Keep the admin table server-paged and manual-sorted. Do not add a display-currency selector or
   use the display-amount projection/global selected currency on this cross-user surface.
5. Update `docs/api-integration.md`, `docs/state-architecture.md`, and `docs/architecture.md` to
   state that `GET /v1/transactions` is the complete active self snapshot held by TanStack Query;
   local filtering/sorting/aggregates operate over it; table pagination is presentation only; view
   IDs intersect that cache; and admin search remains a paged native-value exception.
6. Update admin page/filter/table/URL tests for amount-only, currency-only, and combined searches,
   then run repository-wide searches for documentation or code that still describes user saved
   views as dynamic criteria.

### Implementation notes

Use “raw numeric ordering” or “stored amount” rather than implying the backend performs FX
conversion. The separate admin layout already excludes the ordinary global currency selector;
preserve that separation. Document the 10,000-transaction observation as manual characterization
only, and state that no transaction transport pagination or synchronization change is planned.

### Validation

Run focused admin transaction and URL-state tests, format changed files, run `npm run lint:fix`, and
run `git diff --check -- AGENTS.md README.md docs src`.

### Completion criteria

The admin UI exposes currency as an optional search criterion, amount-only and combined searches
match the backend contract without display-currency state, and web owner docs clearly encode the
complete-self-snapshot/static-membership architecture.

## Phase 14: Regenerate the Orchestration-Owned Unified OpenAPI

### Workspace

../orchestration

### Goal

Refresh the canonical unified OpenAPI artifacts from the running services without violating the
single-repository phase boundary.

### Scope

Add a repository-local generation option that skips the generator's sibling-web copy, validate the
script, run it against the updated local stack, and commit only orchestration-owned generated
artifacts for this phase.

### Non-goals

Changing routing, Kubernetes manifests, service behavior, the Session Gateway contract, or writing
the web repository from this phase.

### Required context

Read `AGENTS.md`, `scripts/README.md`, `docs-aggregator/README.md`, and
`docs/development/getting-started.md`. Before any cluster access, satisfy the documented local Kind
context and loopback API safety checks and confirm the updated service deployments are healthy.

### Execution steps

1. Extend `scripts/repo/generate-unified-api-docs.sh` with a documented option such as
   `--skip-web-copy`; default behavior must remain unchanged. The option must still generate
   `docs-aggregator/openapi.json` and `.yaml` but must not write any sibling repository.
2. Add or update focused shell-script tests if the repository has a harness for argument parsing;
   otherwise test default/help/invalid-option and skip-copy behavior with the lightest safe fixture
   that does not require production credentials.
3. Verify the Kubernetes context is the approved local Kind environment, confirm transaction,
   currency, permission, and session-gateway pods are healthy, then run the generator with the new
   skip-copy option.
4. Inspect the generated diff for only intentional static-view schema/path changes and native
   amount descriptions. Confirm unrelated service schemas and the session gateway are unchanged.
5. Update `docs-aggregator/README.md` and script documentation for the repository-local option.

### Implementation notes

Do not regenerate against staging or production and do not hand-edit generated OpenAPI. If the
local stack or required `kubectl`, `jq`, or YAML tooling is unavailable, stop and report the exact
prerequisite instead of fabricating the artifacts.

### Validation

Run `bash -n scripts/repo/generate-unified-api-docs.sh`,
`shellcheck scripts/repo/generate-unified-api-docs.sh`, the focused script tests, JSON/YAML syntax
checks for both unified outputs, and `git diff --check -- scripts docs-aggregator`.

### Completion criteria

The orchestration-owned unified spec matches the updated live services, the generator can operate
without sibling writes, shell validation passes, and no web file was changed from this phase.

## Phase 15: Synchronize the Web API Snapshot and Run Full Validation

### Workspace

../budget-analyzer-web

### Goal

Copy the reviewed unified contract into the web documentation, verify cross-layer consistency, and
finish with the complete frontend validation gate.

### Scope

Refresh the generated unified API snapshot from the orchestration-owned artifact, reconcile any
last contract mismatches, and run formatting, lint, focused tests, coverage, type checking, and the
production build.

### Non-goals

New product behavior, manual OpenAPI edits, orchestration changes, or relaxing existing tests and
coverage gates.

### Required context

Read `AGENTS.md`, `docs/api-integration.md`, `docs/testing-guide.md`, the reviewed
`../orchestration/docs-aggregator/openapi.yaml`, and all changed web files. Confirm Phase 14 was
completed from the intended updated local services.

### Execution steps

1. Mechanically copy `../orchestration/docs-aggregator/openapi.yaml` to
   `docs/api/budget-analyzer-api.yaml`; do not copy or change the Session Gateway specification when
   its generated content is unchanged.
2. Compare view request/response paths and native admin amount descriptions with the TypeScript
   adapters, forms, tests, and error-message mappings. Fix only real mismatches in this repository.
3. Search all production source/docs for dynamic saved-view concepts, per-ID member fetches,
   `amountInUsd`, raw currency fallback, or claims that the self transaction table is server-paged;
   remove or explicitly mark historical occurrences.
4. Format changed source files, run `npm run lint:fix`, then run the full `npm run build` gate and
   inspect coverage, TypeScript, and Vite output. Run any additional strict-CSP gate required by the
   web instructions if changed UI primitives affect overlays or dropdowns.
5. Run Markdown diff checks and verify every changed local link and documented command. Record any
   unavailable browser/full-stack verifier explicitly rather than claiming it passed.

### Implementation notes

The unified spec is generated documentation; retain its mechanical formatting. The full build
already includes the complete coverage suite and bundle type check. Do not replace it with only the
focused tests from earlier phases.

### Validation

Run:

```bash
npx prettier --check "src/**/*.{ts,tsx,css}" "e2e/**/*.ts" "playwright.config.ts"
npm run lint:fix
npm run build
git diff --check -- AGENTS.md README.md docs src e2e
```

Also inspect `git status --short` and confirm every changed file belongs to the static view,
display-amount, full-snapshot, native-admin, or generated-contract work.

### Completion criteria

The web matches the reviewed generated API, all selected-currency and static-membership paths pass
the full frontend gate, documentation is internally consistent, and any unrun environmental
verifier is reported precisely.

Run this plan from the Transaction Service repository root with:

```bash
ai-session-handler run \
  --plan docs/plans/static-saved-views-and-display-amount.md \
  --max-phases 999 \
  --quiet \
  --agent-cmd "../ai-session-handler/.venv/bin/ai-session-handler-codex-high"
```
