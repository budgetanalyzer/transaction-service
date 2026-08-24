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
