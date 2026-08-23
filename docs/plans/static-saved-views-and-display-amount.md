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

The saved-view API is deliberately breaking. `POST /v1/views` accepts `name` and
`transactionIds`; an empty membership is valid. Membership is an unordered set. The service
canonicalizes duplicate IDs and rejects the complete create or add operation when any requested
addition is not an active transaction owned by the caller. `PATCH /v1/views/{id}/transactions`
applies disjoint `addTransactionIds` and `removeTransactionIds` sets atomically; removal is
idempotent and may remove retained membership for a now-soft-deleted transaction. There is no
criteria, `openEnded`, pin, exclusion, source lineage, or full-membership replacement operation.
Delta mutation limits the multi-writer lost-update surface, so view revisions and `ETag`/
`If-Match` are deferred until concurrent-edit evidence warrants that protocol.

Existing views are frozen at cutover as `(current backend criteria matches - exclusions) union
pins`, restricted to active transactions owned by the view owner. This is the only membership the
system can reconstruct. It preserves what the old backend would show immediately before cutover;
it cannot reconstruct the browser-visible set, selected display currency, or rates used when the
view was originally created.

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

Phases 1 and 2 are one release unit: do not deploy the intermediate schema after Phase 1 without
the Phase 2 cutover. Each phase otherwise leaves focused validation passing, and repository changes
remain confined to the declared workspace.

## Phase 1: Freeze Legacy Membership in a Relational Table

### Workspace

.

### Goal

Add and verify a PostgreSQL migration that materializes each legacy dynamic saved view into an
explicit relational membership set without yet changing application behavior.

### Scope

Create `saved_view_transaction(view_id, transaction_id, added_at)` with a composite primary key,
foreign keys to `saved_view` and `transaction`, cascade only from view deletion, and indexes needed
for membership lookup and active counting. Backfill the exact effective backend membership that
exists at migration time. Add a PostgreSQL/Testcontainers migration test.

### Non-goals

Changing the Java entity, repository, service, HTTP API, frontend behavior, or dropping the legacy
saved-view columns. This intermediate migration is not independently deployable.

### Required context

Read `AGENTS.md`, `docs/database-schema.md`, `docs/domain-model.md`, `docs/saved-views.md`, the full
ordered `src/main/resources/db/migration/` history, `SavedView`, its converters,
`TransactionCriteria.fromViewCriteria`, `TransactionSpecifications`, and the shared
`../service-common/docs/code-quality-standards.md` and `testing-patterns.md` instructions before
editing Java tests.

### Execution steps

1. Add `V22__materialize_saved_view_membership.sql` and create the membership table with a
   composite `(view_id, transaction_id)` primary key, `ON DELETE CASCADE` for the view FK, a normal
   transaction FK so soft-deleted rows remain referentially valid, and an index beginning with
   `transaction_id` for reverse lookup and deletion diagnostics.
2. Backfill one deduplicated row for every active, owner-scoped effective member. Mirror the old
   server semantics exactly: native stored amount comparisons; inclusive date bounds;
   `CURRENT_DATE` for open-ended views without `dateTo`; case-insensitive description word-OR
   substring search with escaped wildcard characters; account/bank substring sets; currency exact
   sets; type matching; exclusions removed; and valid pins unioned back in.
3. Use cutover time as `added_at` and add SQL comments that this timestamp records materialization,
   not the historical moment the user selected a transaction. Retain the legacy criteria,
   `open_ended`, `pinned_ids`, and `excluded_ids` columns for the next phase.
4. Add a focused Flyway migration integration test that migrates a PostgreSQL container to V21,
   inserts representative legacy views and transactions, applies V22, and verifies null versus
   empty criteria collections, multiple search words, escaped `%`/`_`, native amounts, open-ended
   dates, pins, exclusions, duplicate membership, soft deletion, and wrong-owner IDs.
5. Update the migration inventory portion of `docs/database-schema.md` to mark the membership table
   as staged for the static-view cutover; do not yet describe the application as static.

### Implementation notes

Do not attempt to reproduce frontend filtering in this migration. The honest migration target is
the old backend resolver because the original frontend result and display currency were never
persisted. V16 deleted criteria serialized with the obsolete field names, so V22 only needs to
interpret the active `dateFrom`/`dateTo` JSON shape. Keep the SQL set-based; do not load financial
rows into application memory or log criteria or transaction contents.

### Validation

Run the focused migration test with `./gradlew test --tests '*SavedViewMembershipMigrationTest'`.
Run `git diff --check -- src/main/resources/db/migration docs/database-schema.md src/test` and
inspect the V1-V22 ordering manually. Do not run the full build until the Java cutover is present.

### Completion criteria

V22 reproducibly freezes all tested legacy membership cases, retains legacy columns for the next
phase, and the existing application can still ignore the new table. The phase is clearly marked as
not deployable on its own.

## Phase 2: Cut Saved-View Persistence to Static Membership

### Workspace

.

### Goal

Make the persisted domain model represent saved-view metadata plus relational transaction
membership, with no dynamic-filter state.

### Scope

Drop the staged legacy columns in V23, simplify `SavedView`, add a lightweight membership entity or
repository model, and provide batch-safe repository operations for IDs and active counts.

### Non-goals

Implementing create/add/remove business rules, changing HTTP routes, frontend changes, or changing
transaction soft-delete behavior.

### Required context

Re-read `AGENTS.md`, `docs/domain-model.md`, `docs/database-schema.md`, V22 and its migration test,
`SavedViewRepository`, `TransactionRepository`, and the shared Java architecture, quality, and test
documents required by `AGENTS.md`.

### Execution steps

1. Add `V23__remove_dynamic_saved_view_columns.sql` to drop `criteria`, `open_ended`, `pinned_ids`,
   and `excluded_ids` only after V22 has materialized their effective membership.
2. Reduce `SavedView` to ID, owner, name, and timestamps. Remove `ViewCriteria`, the saved-view JSON
   converters, and pin/exclusion mutators when no remaining runtime use exists; preserve the
   intentional API-bound `TransactionFilter` layering exception for administrative search.
3. Model `saved_view_transaction` without placing a 10,000-row collection on `SavedView`. Prefer a
   composite ID with scalar view and transaction identifiers plus `addedAt`, so ID queries do not
   hydrate full `Transaction` entities.
4. Add repository operations for deterministic active member IDs, total/active counts grouped by
   owner-visible views, idempotent bulk delete by view and transaction IDs, and batch insertion.
   Active reads must join to `transaction.deleted = false`; stored membership itself must retain
   soft-deleted IDs.
5. Extend repository and migration integration tests to prove final V23 schema shape, FK/cascade
   behavior, duplicate rejection, owner-scoped view lookup, active-count behavior, and retained
   membership after a transaction is soft-deleted.

### Implementation notes

Keep controllers free of persistence details and do not expose the association entity through API
models. Avoid an eager or lazy `@OneToMany` collection on `SavedView`; both invite accidental
hydration of the complete membership. Use the simplest JPA batching that handles the characterized
10,000-ID create path before considering native insert SQL.

### Validation

Run focused repository and migration tests, including
`./gradlew test --tests '*SavedView*Repository*' --tests '*SavedViewMembershipMigrationTest'`.
Run Spotless on changed Java files through the project task and inspect Checkstyle output from the
focused test execution.

### Completion criteria

The latest schema has no dynamic saved-view columns, the Java domain has no dynamic saved-view
state, and repository tests prove static membership storage without hydrating full transactions.

## Phase 3: Implement Atomic Static-Membership Services

### Workspace

.

### Goal

Replace dynamic resolution, pins, and exclusions with owner-scoped create, read, count, add, and
remove business operations over explicit membership.

### Scope

Refactor `SavedViewService` and its service-layer command/result models. Implement all-or-nothing
validation for membership creation and additions, idempotent removals, efficient list counts, and
soft-delete-aware reads.

### Non-goals

Changing controller routes or API models, adding full-set replacement, source-view lineage,
ordering, duplicate membership, optimistic HTTP revisions, or cross-user view operations.

### Required context

Read `AGENTS.md`, the shared Spring conventions, code quality, error handling, and testing pattern
documents, then inspect `SavedViewService`, `TransactionService`, the new membership repositories,
and current service and integration tests. Preserve authentication-owner boundaries supplied by
the controller.

### Execution steps

1. Replace `SavedViewCommand` with a create command containing a name and transaction ID collection;
   canonicalize it to an unordered set and allow an empty set.
2. Make create transactional. Resolve all unique requested IDs through the existing active-owner
   repository boundary and reject the complete operation with a non-leaking stale-snapshot conflict
   when the counts differ; create neither the view nor partial membership on failure.
3. Replace `resolveView`, criteria querying, and pin/exclusion methods with deterministic active-ID
   reads, grouped active counts, name-only updates, and one transactional membership-delta method.
   Require disjoint add/remove sets, validate every addition atomically, make unknown removals a
   no-op, and update the view timestamp when membership changes.
4. Ensure list/get responses can obtain active counts without one dynamic transaction query per
   view. Keep persisted membership for soft-deleted transactions while excluding those IDs and
   counts from normal reads.
5. Rewrite service tests with real objects and Spring integration coverage where persistence
   behavior matters. Cover empty create, duplicates, a 10,000-ID static-membership create, wrong
   owner, deleted IDs, rollback on one invalid addition, idempotent removal, overlapping delta
   rejection, and list-count query behavior. The large create case validates the new membership
   request/persistence path; it is not a transaction-snapshot transport test.

### Implementation notes

Use `409 Conflict` for a syntactically valid create/add request based on a stale client snapshot and
`400 Bad Request` for an invalid delta shape. Do not return which inaccessible IDs belong to other
users. Membership removal only addresses rows under the already owner-checked view and therefore
does not need to load or reactivate a transaction. Keep batch logs to counts and view IDs; never log
financial row contents.

### Validation

Run `./gradlew test --tests '*SavedViewServiceTest' --tests '*SavedViewServiceIntegrationTest'` and
any focused repository tests changed in this phase. Run Spotless and fix all Checkstyle warnings
shown by the focused suite.

### Completion criteria

The service layer has no dynamic-filter path, all additions are owner-scoped and atomic, removals
are idempotent, active counts are efficient, and the 10,000-member service case is correct without
a timing assertion.

## Phase 4: Publish the Breaking Static Saved-View API

### Workspace

.

### Goal

Expose the static collection contract through validated, authorized HTTP operations and generated
runtime OpenAPI.

### Scope

Replace saved-view request/response models and routes, retain existing fine-grained permissions,
and update controller authorization and OpenAPI integration tests.

### Non-goals

Backward-compatible dynamic endpoints, transport pagination, a source-view query parameter,
membership ordering, `ETag` support, or frontend implementation.

### Required context

Read `AGENTS.md`, `docs/api/README.md`, the Permission Service authorization model named there,
shared error/testing documentation, `SavedViewController`, all saved-view API records, controller
authorization tests, and `TransactionOpenApiIntegrationTest`.

### Execution steps

1. Change create to `POST /v1/views` with `{ "name": string, "transactionIds": number[] }` and
   return `201`, `Location`, and a metadata response containing ID, name, active transaction count,
   and timestamps. Validate a nonblank bounded name, a non-null ID list, positive non-null IDs, and
   permit an empty list.
2. Make update name-only. Replace the membership response with
   `{ "transactionIds": number[] }`, sorted deterministically and containing active members only.
   Remove criteria, open-ended, matched, pinned, excluded, and their counts from all response
   schemas.
3. Add `PATCH /v1/views/{id}/transactions` with non-null
   `addTransactionIds`/`removeTransactionIds` arrays, at least one nonempty array, positive IDs, and
   disjoint canonical sets. Return updated metadata or a compact delta result consistently; do not
   add a full membership replacement operation.
4. Delete all single and bulk pin/exclude routes. Keep `views:read`, `views:write`, and
   `views:delete` method security at the same capability boundaries and source actor identity only
   from `SecurityContextUtil`.
5. Rewrite controller, authorization, validation, error-response, and OpenAPI tests to prove the
   exact breaking schemas, 409 atomic stale-snapshot behavior, 404 owner isolation, removed routes,
   and absence of dynamic fields from generated components.

### Implementation notes

Do not place user identity in request bodies. The membership endpoint returns IDs rather than
transaction objects because the browser already owns the complete self-scoped snapshot. Keep
`PATCH` atomic and idempotent for repeated identical deltas. If an existing application error code
is not suitable for stale membership, add the narrow service-owned error according to shared error
handling and document it for the web client.

### Validation

Run focused controller, authorization, and OpenAPI suites, including
`./gradlew test --tests '*SavedViewController*' --tests '*TransactionOpenApiIntegrationTest'`.
Inspect the generated OpenAPI JSON assertions for removed pin/exclude paths and exact create/delta
schemas.

### Completion criteria

The runtime contract exposes only static membership, every application endpoint remains
fine-grained-authorized, old dynamic routes are absent, and focused HTTP/OpenAPI tests pass.

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
   `maxAmount`, and `sort=amount,...` compare each row's stored numeric value in that row's native
   currency. Mixed-currency ordering is numeric-unit ordering, not one economically comparable
   amount; useful economic interpretation requires narrowing to one currency.
3. Remove saved-view-only `ViewCriteriaApi`, `ViewCriteria`, converters, service DTOs, and
   `TransactionCriteria.fromViewCriteria` after verifying they have no remaining callers. Keep JPA
   Specifications and `TransactionFilter` for advanced administrative search.
4. Rewrite `docs/saved-views.md` as the static collection owner document and update
   `docs/api/README.md`, `docs/domain-model.md`, and `docs/database-schema.md`. Add a resolution note
   to the divergence issue that preserves its diagnosis but supersedes its dynamic clause design.
   Remove the obsolete `docs/plans/saved-view-save-as.md` and mark the related source-plan review as
   superseded rather than rewriting it as current architecture.
5. Run the mandatory backend formatting and full-build sequence and inspect all output for
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
snapshot is explicit in owner docs, native admin amount changes are covered by their relevant
tests/docs, and obsolete executable dynamic-view guidance is removed.

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
   transactionIds}`, membership `{transactionIds}`, name update, and membership delta request/
   result types matching the backend OpenAPI.
2. Update `viewApi` for create, membership read, name update, and
   `PATCH /v1/views/{id}/transactions`; remove every pin/exclude adapter method.
3. Refactor view hooks to wait for the canonical `['transactions']` snapshot, build one ID map, and
   intersect active membership locally. Delete the per-ID missing-member request fan-out; a missing
   ID represents stale/soft-deleted membership and is not a fetch trigger.
4. Implement mutation cache handling that invalidates or updates view metadata and membership
   together after a successful atomic operation, and refreshes both plus the transaction snapshot
   after a stale-snapshot conflict.
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
render and submit. On 409, refresh and explain that the visible set changed; do not retry with a
silently reduced ID list.

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
   member disappears because it is absent from the complete active snapshot, not because the UI
   edits membership.
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
   conflict errors, and return to the view after success with refreshed membership and counts.
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

Make the paged administrative search visibly distinct from the selected-currency user experience
and document the complete-snapshot frontend architecture.

### Scope

Update administrative amount labels/help and currency narrowing, revise web architecture/API/state
documentation, and run focused UI validation before the generated API snapshot is refreshed.

### Non-goals

Client-side conversion of administrative results, loading every user's transactions, backend
behavior changes, or final full build against a stale generated OpenAPI file.

### Required context

Read `AGENTS.md`, `docs/api-integration.md`, `docs/state-architecture.md`, `docs/architecture.md`,
`docs/testing-guide.md`, the admin transaction search page/filter/table/URL modules, and the current
runtime OpenAPI descriptions from Phase 5.

### Execution steps

1. Rename the admin column and range controls to “Native amount” and add concise help that each
   value is in the row's displayed ISO currency. Explain that mixed-currency numeric sorting is not
   an economic ranking.
2. Expose the already-supported `currencyIsoCode` administrative filter in the filter panel and
   retain it in URL state. When native amount bounds or amount sorting are active without a single
   currency, show a nonblocking warning that narrowing currency makes the comparison meaningful.
3. Keep the admin table server-paged and manual-sorted. Do not use the display-amount projection or
   global selected currency on this cross-user surface.
4. Update `docs/api-integration.md`, `docs/state-architecture.md`, and `docs/architecture.md` to state
   that `GET /v1/transactions` is the complete active self snapshot held by TanStack Query; local
   filtering/sorting/aggregates operate over it; table pagination is presentation only; view IDs
   intersect that cache; and admin search remains a paged native-value exception.
5. Update admin page/filter/table/URL tests and run repository-wide searches for documentation or
   code that still describes user saved views as dynamic criteria.

### Implementation notes

The admin warning clarifies semantics; it must not block valid forensic searches across currencies.
Use “native numeric ordering” rather than implying the backend performs FX conversion. Document
the 10,000-transaction observation as manual characterization only, and state that no transaction
transport pagination or synchronization change is planned.

### Validation

Run focused admin transaction and URL-state tests, format changed files, run `npm run lint:fix`, and
run `git diff --check -- AGENTS.md README.md docs src`.

### Completion criteria

The admin UI cannot be mistaken for selected-currency economic sorting, and web owner docs clearly
encode the complete-self-snapshot/static-membership architecture.

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
