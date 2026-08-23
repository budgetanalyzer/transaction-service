# Transaction-Filter Contract Divergence and Systemic Design Defect

**Status:** Open
**Severity:** Major correctness and design defect
**Affected surfaces:** transaction tables, transaction search, ordinary saved-view creation,
saved-view membership, and source-assisted saved-view creation
**Blocked plan:** [`docs/plans/saved-view-save-as.md`](../plans/saved-view-save-as.md)
**Related review:**
[`saved-view-create-from-source-plan-review.md`](saved-view-create-from-source-plan-review.md)

## Summary

The Budget Analyzer does not have one transaction-filter contract. The active web tables, web
amount sorting, an unused web saved-view helper, and transaction-service repository predicates
interpret the same apparent filter fields differently.

This is already a user-visible correctness defect in ordinary saved-view creation. A saved view can
contain rows that were not visible when the user selected **Save as View**, omit rows that were
visible, or apply numeric amount bounds to incomparable native-currency values. The defect is not
limited to the proposed source-assisted creation feature; that feature merely exposed that the
current filter and persistence design cannot express the product's exercised behavior.

The planned source-assisted creation work must remain blocked, but resolving that plan is not the
mitigation. The system first needs a versioned, documented filter contract; an explicit amount and
currency basis; a persisted saved-view definition that can represent conjunctions; coordinated web
and backend changes; and an existing-view migration or compatibility decision.

## User Impact

- Ordinary saved-view creation does not reliably preserve the rows shown at creation.
- Saved-view membership can broaden or narrow when local web filters are serialized into fields
  with different backend semantics.
- Amount bounds have no declared currency basis. Comparing `100 USD`, `100 EUR`, and `100 THB` to
  one numeric bound treats incomparable values as equivalent.
- The web amount column sorts by converted USD value but filters by unconverted native value, so
  filtering and sorting the same column use different quantities.
- The backend filters signed native values, while the web filters absolute native values.
- A source view refined locally cannot always be persisted as one flat `ViewCriteria`.
- The current membership response cannot tell the web which every stored pin is, so the client
  cannot correctly reconstruct retained pin intent.

## Exercised Web Behavior

The active web paths are:

- `budget-analyzer-web/src/features/transactions/pages/TransactionsPage.tsx` loads current-user
  transactions, applies `filterTransactions(...)`, and copies active local filters into a
  `ViewCriteriaApi` for ordinary saved-view creation.
- `budget-analyzer-web/src/features/views/pages/ViewPage.tsx` loads canonical saved-view membership
  through `useViewTransactions(id)` and applies the same local filters. A local refinement is
  therefore ANDed with the source view's effective membership.
- `budget-analyzer-web/src/utils/transactionFilters.ts` applies date, search, bank, account, type,
  and amount filters sequentially. Active dimensions are combined with AND.
- `budget-analyzer-web/src/utils/transactionSearch.ts` trims and lowercases the complete `q` value,
  then tests `description.includes(query)`. It does not split the query into terms.
- Bank names, account IDs, and transaction types use JavaScript strict equality.
- Amount bounds compare `Math.abs(transaction.amount)` without currency conversion.
- `TransactionTable.tsx` and `ViewTransactionTable.tsx` separately derive `amountInUsd` using the
  transaction date and available exchange rates. That derived value is used for amount sorting,
  not for the local amount filter.
- The table can display a user-selected currency, while amount sorting remains explicitly based on
  USD. Display, sorting, and filtering therefore need not use the same currency.
- The active local filter bar has no currency filter. Serializing an amount range into a saved view
  therefore does not automatically constrain membership to one currency.

For example, when a source view has `searchText="coffee"` and its detail page has local
`q=starbucks`, ordinary matched rows visible in the web satisfy:

```text
description contains "coffee"
AND
description contains "starbucks"
```

Within one `q` value, `coffee starbucks` means the complete contiguous substring
`"coffee starbucks"`; it does not mean `coffee OR starbucks` or independently tokenized
`coffee AND starbucks`.

The apparent web implementation of saved-view OR semantics is not an exercised application path:

- `budget-analyzer-web/src/utils/filterTransactions.ts` splits saved `searchText` through
  `parseSearchTerms(...)` and applies OR matching.
- Direct repository search found no production caller of `filterTransactionsByCriteria(...)`.
- `parseSearchTerms(...)` is consequently used only by that unused helper and its unit tests.

The unused helper must not be treated as evidence of the active UI contract.

## Current Transaction-Service Behavior

`TransactionSpecifications.withCriteria(...)` currently implements:

- AND between different criterion fields;
- case-insensitive exact OR matching for currency codes;
- case-insensitive substring OR matching for values in `accountIds` and `bankNames`;
- whitespace splitting followed by case-insensitive substring OR matching for each account ID,
  bank name, description, and saved-view `searchText` value; and
- inclusive comparisons of `minAmount` and `maxAmount` against the signed stored amount, without
  absolute-value normalization or currency conversion.

`TransactionCriteria` and `TransactionSpecifications` are shared by saved-view membership and
ordinary transaction-search paths. Changing their generic text or amount behavior without
separating the contracts could therefore change APIs beyond saved views.

The transaction service does not currently resolve historical exchange rates when matching
transactions. A normalized-currency saved-view predicate would require a deliberate architecture:
for example, coordinated Currency Service access, locally persisted normalized amounts with clear
rate provenance, or another authoritative evaluation boundary. A fallback to the raw amount when
a rate is unavailable would make membership silently incorrect and must not become the contract.

## Contract Divergence Matrix

| Concern | Active web filter | Web amount sort | Transaction service |
| --- | --- | --- | --- |
| Search text | One literal case-insensitive description substring | N/A | Whitespace terms ORed as case-insensitive substrings |
| Bank | Case-sensitive exact selected value | N/A | Case-insensitive substring terms ORed |
| Account | Case-sensitive exact selected value | N/A | Case-insensitive substring terms ORed |
| Currency | No active local table filter | N/A | Case-insensitive exact values ORed when supplied |
| Amount sign | Absolute value | Signed converted value | Signed stored value |
| Amount currency | Native transaction currency | Converted to USD | Native transaction currency |
| Missing exchange rate | Not needed | Conversion helper returns the original amount | No conversion attempted |
| Filter dimensions | AND | N/A | AND |

This matrix describes exercised behavior, not desired behavior. None of the three amount meanings
should be declared canonical merely because it exists today.

## Existing Correctness Defects

### Multiword search broadens membership

```text
Web q:              "coffee starbucks"
Rows shown by web:  descriptions containing the complete phrase "coffee starbucks"
Saved criteria:     searchText="coffee starbucks"
Backend membership: descriptions containing "coffee" OR "starbucks"
```

The saved view can include transactions that were not visible when it was created.

### Bank and account selections change meaning

The web uses exact equality for selected bank and account filters. The backend uses
case-insensitive substring matching and splits whitespace within each selected value.

For example, a web selection of `Capital One` is persisted as `bankNames=["Capital One"]`, while
the backend predicate matches a bank containing `Capital` OR `One`. Account IDs are also treated as
case-insensitive substrings instead of exact identifiers.

### Amount sign changes meaning

The web applies inclusive bounds to `abs(amount)`. The backend applies the same serialized bounds
to the signed stored amount. For a stored amount of `-75` and bounds `50..100`, the web includes the
row while the backend excludes it.

If transaction amounts are intended to be nonnegative magnitudes with `type` carrying direction,
that invariant is not sufficient as an undocumented assumption. It must be validated at every
write boundary, enforced for persisted data, documented, and tested. Otherwise absolute-versus-
signed comparison remains part of the canonical filter decision.

### Amount bounds lack a currency basis

The web applies one amount range independently to each transaction's native numeric amount. A
mixed-currency table therefore treats equal numeric values as equal purchasing amounts. The
serialized `minAmount` and `maxAmount` fields carry neither a currency nor a conversion policy, so
the backend cannot infer whether the user meant native currency, USD, or the selected display
currency.

Adding a currency criterion would make native-currency bounds coherent only when the view is
restricted to exactly one currency. It does not solve mixed-currency amount filtering.

### Amount sorting and filtering disagree in the web

The amount column derives a transaction-date USD value for sorting after the page has already
filtered transactions by absolute native amount. A user can therefore filter by one quantity and
then observe an order based on another quantity under the same **Amount** label.

The conversion helper returns the original amount when no suitable rate exists. That fallback may
be acceptable for best-effort presentation, but it cannot define persisted filter membership
because it mixes currencies while appearing normalized.

## Root Design Problems

The defects share structural causes:

1. Filter field names describe values but not operators. `searchText`, `bankNames`, `accountIds`,
   `minAmount`, and `maxAmount` do not state tokenization, equality, case handling, sign handling,
   or currency basis.
2. Local web filtering and repository filtering are independent implementations without shared
   executable contract cases.
3. Saved-view criteria reuse generic repository predicates whose semantics also serve transaction
   search.
4. One flat `ViewCriteria` assumes at most one predicate per dimension and cannot retain arbitrary
   conjunctions produced by refining an existing view.
5. `openEnded` is stored outside `ViewCriteria`, even though it changes the effective date
   predicate and is part of the saved definition.
6. Saved-view membership exposes effective membership groups, not complete raw override intent.
7. Numeric amount fields omit the comparison currency and exchange-rate policy.

## Why Source-Assisted Creation Is Not Representable

The reviewed plan defines the request body as a complete independent target definition. The web
workflow instead starts with canonical source membership and applies additional local filters. The
effective ordinary target predicate is:

```text
source definition (criteria plus openEnded behavior)
AND
local refinement definition
```

The current `ViewCriteria` cannot represent every such conjunction:

- One `searchText` cannot express two independent literal substring predicates joined by AND.
- A source bank/account OR-set refined by an exact local selection becomes an intersection that is
  not equivalent to replacing or unioning the stored set.
- A local bank, account, or type filter may match only a pinned source transaction that overrides a
  conflicting source criterion. The correct ordinary target predicate is then false while the pin
  remains included. Empty stored sets currently mean an absent constraint, not match-none.
- Amount clauses may have different or undeclared currency bases and cannot safely be intersected
  as plain numbers.
- A source `openEnded=true` clause with no stored `dateTo` advances with the current date. Resolving
  it once during creation would freeze behavior that is currently dynamic. A clause representation
  must preserve open-ended behavior per applicable clause, or compile it into another precisely
  equivalent dynamic form.
- Concatenating text values or comparing normalized flat definitions cannot recover the missing
  conjunction structure.

Consequently, SVV-002 and SVV-003 in the related review are downstream of this systemic contract
problem. A changed-field criteria reconciler cannot make an unrepresentable target correct.

## Pin Authority and Membership Classification

The web cannot reconstruct every visible stored source pin from the membership response.

`SavedViewService.resolveView(...)` places a stored pin in `matched` when the transaction also
matches ordinary criteria. The `pinned` response group contains only active stored pins that do not
already match. The web's retained `MATCHED` versus `PINNED` classification therefore represents
the reason an ID appears in effective membership, not complete persisted pin status.

Submitting only rows classified as `PINNED` would discard stored pin intent for pins currently
classified as `MATCHED`. That can change future membership after transaction edits or later view
edits. Adding all visible IDs would instead materialize ordinary membership and is also incorrect.

The backend already owns the authoritative raw source pin set. Once the request carries an explicit
local refinement with canonical semantics, the robust rule is:

```text
target pins = raw stored source pins
              AND active
              AND owned by the authenticated user
              AND matching the local refinement
```

The local refinement, not the complete target definition, applies to pins because pins continue to
override the copied source definition. This preserves stored pin intent without trusting client-
supplied membership IDs. All raw stored source exclusions can continue to be copied so historical
exclusion intent survives future definition changes.

## Concurrency Is a Whole-Source Concern

Concurrent changes are not limited to source pins. Between loading the view page and submitting
creation, any of the following can change:

- source criteria or `openEnded` behavior;
- source pins;
- source exclusions;
- transaction fields used by the local refinement; or
- transaction active/deleted state.

Combining client-observed pins with server-current criteria and exclusions creates a definition
from inconsistent moments. The contract must choose between:

- evaluating the source and transactions from current server state when the creation request runs;
- requiring an expected source version and rejecting source-definition or override changes; or
- providing a stronger snapshot mechanism with explicitly bounded scope.

An `@Transactional` service method guarantees all-or-nothing target persistence but does not, under
ordinary PostgreSQL isolation, guarantee that the source remained unchanged since the web loaded
it. The mitigation must not call that behavior a client-observed snapshot.

Transaction mutations are a separate race from source-view mutations. Because the target remains
dynamic, exact reproduction of an earlier client display cannot be promised without materializing
or snapshotting transaction state. The product contract should state the accepted boundary.

## Required Contract Decisions

Before implementation or plan revision, decide:

1. Whether saved search is one literal substring, ORed tokens, or another explicit grammar.
2. Whether bank and account values use exact or substring matching, including case behavior and OR
   semantics across multiple values.
3. Whether stored amounts are signed values or enforced nonnegative magnitudes.
4. Whether amount bounds compare native values, normalized USD values, or a declared comparison
   currency.
5. If conversion is required, which service owns evaluation, which dated rate is authoritative,
   what provenance is retained, and what happens when no rate exists.
6. Whether amount sorting, display, local filtering, backend search, and saved-view membership must
   intentionally use the same quantity or may expose explicitly different quantities.
7. How an independent saved view persists multiple ANDed criteria clauses and an always-false
   intersection.
8. How `openEnded` behavior is represented when more than one criteria clause exists.
9. Whether source-assisted creation submits a local refinement or a complete independently
   representable target definition.
10. Whether the backend filters authoritative raw source pins by the local refinement.
11. Whether creation uses current server state or detects source changes through a version
    precondition.
12. How existing saved views written under current OR/substr/signed-native semantics are migrated
    or preserved.
13. Whether generic transaction-search predicates intentionally retain different semantics from
    saved views and, if so, how the internal models prevent accidental reuse.

## Recommended Mitigation Direction

Treat this as its own cross-repository mitigation before returning to source-assisted creation.

### Define a versioned executable contract

Document every operator, normalization rule, null/blank behavior, sign rule, currency basis,
exchange-rate rule, and conjunction rule. Maintain shared language-neutral fixture cases that both
Java and TypeScript tests execute. Unit tests written independently from prose are insufficient to
prevent another drift.

### Separate filter purposes where semantics differ

Do not silently change generic transaction-search behavior while correcting saved views. If admin
or API search intentionally uses tokenized substring predicates but saved views use literal/exact
predicates, represent those as different internal contracts rather than routing both through an
ambiguous field model.

### Introduce a saved-definition representation

An explicit list of ANDed definition clauses is the smallest direct representation. Each clause
must carry criteria semantics, amount/currency semantics, and applicable open-ended date behavior.
Ordinary creation stores one clause; source-assisted creation copies the independent source clauses
and appends the local refinement without storing source lineage.

A compiled purpose-built representation is also possible, but it must precisely represent required
literal search clauses, exact-set intersections, range intersections, dynamic dates, and
`matchNone`. A generic predicate AST is unnecessary unless other product requirements justify it.

### Keep override reconciliation authoritative

Have the backend filter raw stored source pins by the explicit local refinement, ownership, and
active state. Copy raw exclusions. Do not derive stored pin intent from effective membership labels
and do not submit ordinary visible membership IDs.

### Version or deliberately migrate existing views

If existing behavior must remain stable, the persisted representation needs legacy predicate
semantics that can be combined with a new refinement clause. A view-level version is insufficient
if one target can contain a legacy source clause and a new canonical refinement clause; semantics
must be preserved at the clause or compiled-operator level.

If the product accepts correcting existing views to the behavior users saw during creation, perform
and document a deliberate semantic migration. Do not silently reinterpret or delete saved views.

## Plan Impact

The existing source-assisted creation plan must not be patched incrementally or executed. A new
mitigation plan should precede its replacement and should:

- coordinate transaction-service, `budget-analyzer-web`, and any required Currency Service
  contract or data changes;
- correct ordinary table/filter/save behavior before adding source-assisted creation;
- introduce the versioned filter and persistence contract;
- resolve amount sign and currency semantics end to end;
- preserve or migrate existing views explicitly;
- replace the changed-field reconciler with explicit source-definition plus local-refinement
  composition;
- retain pins from the authoritative raw source set rather than client membership labels;
- define source and transaction concurrency boundaries;
- add shared cross-repository contract fixtures and end-to-end cases; and
- retain the no-lineage and top-level resource decisions already recorded in SVV-001 where they
  remain compatible with the corrected request model.

The mitigation will likely require schema or persisted-JSON changes. The original plan's assumption
that no migration is needed is no longer credible.

## Historical Evidence

The web historically used OR term parsing. Commit `61dc69f` (`Saved view tightening (#87)`)
replaced the exercised Transactions and View table search with full-string, description-only
substring matching. Transaction-service commit `43c60ca` (`Consistent view search semantics
(#66)`) retained multiword OR predicates. Those coordinated changes left the active applications
with different search semantics.

Web commit `84d7f1b` (`Sort by dollars (#98)`) added transaction-date USD conversion for amount
sorting. It did not change `filterTransactions(...)`, which continues to compare absolute native
amounts. This created an additional divergence between filtering and sorting under the same table
column.

This history explains how the contracts diverged; it does not make any current implementation the
desired canonical behavior.

## Verification Performed

Static tracing covered:

- the web create adapter, Transactions page, View page, shared local filters, and URL filter state;
- both transaction-table amount-column definitions and their USD sort tests;
- currency conversion and missing-rate fallback behavior;
- web membership reconciliation and `MATCHED`/`PINNED` classification;
- saved-view API types and the unused saved-criteria helper;
- transaction-service criteria mapping, specification predicates, saved-view resolution, domain
  persistence, and integration tests; and
- the affected source-assisted creation plan and review issue.

The previously recorded focused web utility run passed without modification:

```text
npx vitest run src/utils/__tests__/transactionSearch.test.ts \
  src/utils/__tests__/transactionFilters.test.ts \
  src/utils/__tests__/filterTransactions.test.ts

Test Files  3 passed
Tests      20 passed
```

Repository tests explicitly cover absolute native-value filtering and USD-equivalent sorting as
separate behaviors. No runtime browser workflow or cross-service end-to-end test was run. No
Currency Service runtime behavior was exercised.

## Resolution Gate

This issue is resolved only when:

1. Every affected web and backend surface documents and tests one intentional versioned contract
   for each shared filter purpose.
2. Search, bank, account, case, sign, and amount-currency semantics are explicit and consistent
   wherever the product presents them as the same filter.
3. Missing exchange-rate behavior cannot silently produce mixed-currency membership.
4. Ordinary saved-view creation reproduces the exercised filter contract instead of changing
   predicate meaning during serialization.
5. The saved definition can represent every supported conjunction, dynamic open-ended clause, and
   match-none result without source lineage.
6. Raw pin authority, exclusions, and source/transaction concurrency boundaries are explicit and
   tested.
7. Existing saved views have a documented compatibility or migration outcome.
8. Shared cross-repository fixtures and end-to-end cases prevent semantic drift.
9. A dedicated mitigation plan is completed and the source-assisted creation plan is rewritten and
   reviewed against the corrected architecture.


Another Agent reviewed:

• ## Recommendation

  Given the product decision that the browser loads the complete current-user dataset and saved views do not automatically absorb future transactions, I would replace dynamic saved views with static, curated transaction collections.

  That is a domain-model change, not merely a filter-contract fix:

  > A saved view is an unordered set of transaction identities chosen by the user. Membership changes only through explicit add/remove operations.

  Under that definition, the issue’s proposed clause model, backend currency conversion, shared filter fixtures, openEnded, pins, exclusions, and source-view reconciliation are unnecessary. The issue’s diagnosis remains correct, but its recommended architecture
  should be superseded.

  The playlist analogy fits membership, cloning, and editing. I would not copy playlist ordering or duplicate-item behavior; transaction views should remain sets.

  ## What the code confirms

  The architecture is already mostly client-first:

  - The self-scoped API returns every transaction in one response: src/main/java/org/budgetanalyzer/transaction/api/TransactionController.java:313.
  - The web filters the complete array and gives the table the filtered result: /workspace/budget-analyzer-web/src/features/transactions/pages/TransactionsPage.tsx:78.
  - The table uses client-side pagination and sorting: /workspace/budget-analyzer-web/src/features/transactions/components/TransactionTable.tsx:296.
  - Cross-user administration is separately paginated and server-filtered. That is a sensible boundary.

  The inconsistency appears only when transient browser state is serialized as a backend predicate. The web turns its current filters into ViewCriteriaApi /workspace/budget-analyzer-web/src/features/transactions/pages/TransactionsPage.tsx:109, while the backend
  later reevaluates those criteria in src/main/java/org/budgetanalyzer/transaction/service/SavedViewService.java:317.

  Static membership removes that translation boundary completely.

  ## Proposed target architecture

   Concern                                                     Authority
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━
   Transient filtering, sorting, search, display conversion    Web application
  ──────────────────────────────────────────────────────────  ─────────────────────
   Dated exchange-rate facts and publication provenance        Currency Service
  ──────────────────────────────────────────────────────────  ─────────────────────
   Transaction truth, ownership, active/deleted state          Transaction Service
  ──────────────────────────────────────────────────────────  ─────────────────────
   Persisted saved-view membership                             Transaction Service
  ──────────────────────────────────────────────────────────  ─────────────────────
   Which visible results the user chooses to save              User/web request

  Creation becomes conceptually:

  {
    "name": "Thailand trip",
    "transactionIds": [101, 104, 109]
  }

  The backend should atomically:

  1. Canonicalize the list as a set.
  2. Confirm every ID is owned by the authenticated user.
  3. Apply the chosen active/deleted rule.
  4. Create the view and its membership.
  5. Reject the complete operation if the client’s observed data has become unavailable. Partial creation would violate “save what I see.”

  Important resulting behavior:

  - Late imports do not enter an existing view, even when their dates would have matched the old criteria.
  - Editing a transaction’s description, bank, account, or amount does not change membership.
  - Soft-deleted members disappear from normal view reads, but their membership row can remain for historical intent.
  - Saving a locally filtered source view simply submits filteredTransactions.map(id).
  - Cloning an unfiltered view submits its current visible IDs.
  - Empty views should probably be allowed, like empty collections.
  - Add/remove APIs replace pin/exclude APIs. There is only membership, not “matched versus pinned.”

  For persistence, use a relational membership table:

  saved_view
  saved_view_transaction(view_id, transaction_id, added_at)

  with (view_id, transaction_id) as the primary key. Do not move 10,000 IDs into the existing JSON pinned_ids field. PostgreSQL’s own guidance says collections of identifiers belong in a separate table because arrays are not sets and scale/search better
  relationally. The current entity stores criteria and ID sets as text columns src/main/java/org/budgetanalyzer/transaction/domain/SavedView.java:37, which would cause whole-row rewrites and poor concurrent-update behavior.

  Use delta operations after creation—bulk add and bulk remove—rather than replacing all 10,000 IDs for every edit. A monotonically increasing view revision or strong ETag would protect multi-tab/device changes. HTTP If-Match is specifically intended to prevent
  lost updates.

  The frontend should also stop fetching missing view members individually. The current hook can fan out one GET per missing ID /workspace/budget-analyzer-web/src/hooks/useViews.ts:91. In the chosen architecture, it should wait for the canonical all-transactions
  query, build one ID map, and intersect it with membership. A missing ID means deleted/stale membership, not “start thousands of requests.”

  ## Currency amounts

  One correction: amount filtering is not based on the selected display currency today. It compares Math.abs(transaction.amount) in the native currency /workspace/budget-analyzer-web/src/utils/transactionFilters.ts:53. Meanwhile, amount sorting explicitly derives
  USD values /workspace/budget-analyzer-web/src/features/transactions/components/TransactionTable.tsx:95.

  Sorting by USD is not equivalent to sorting by the selected display currency. For transaction-date conversions:

  display amount = USD equivalent on transaction date
                   × selected-currency rate on transaction date

  The second factor varies by date, so two transactions can reverse order when the selected currency changes.

  I would define one frontend DisplayAmount computation and make display, filtering, sorting, and statistics consume exactly that result:

  - Convert the amount magnitude into the selected currency using the transaction date.
  - Use Currency Service’s previous-published-rate policy for weekends and holidays. It already returns a dense daily series and exposes publishedDate: /workspace/currency-service/src/main/java/org/budgetanalyzer/currency/service/ExchangeRateService.java:163 and /
    workspace/currency-service/src/main/java/org/budgetanalyzer/currency/api/response/ExchangeRateResponse.java:34.

  - Quantize to the selected currency’s minor units before filtering if the intention is “filter what the user sees.”
  - Use that same quantized value for sorting.
  - Stable-sort equal amounts by date and ID.
  - Treat conversion failure as unavailable, never as the original numeric amount in the target currency. The current fallback silently returns the native amount /workspace/budget-analyzer-web/src/utils/currency.ts:142, which is unsuitable for filtering, sorting,
    or totals.

  - Disable saving an amount-filtered view while required rates are loading or unavailable, or require explicit acknowledgement of excluded unavailable rows.

  Changing display currency while an amount range is active also needs a decision. There is no single correct conversion of the bounds because every transaction uses a different date’s rate. My preference is:

  - include amountCurrency in the URL contract; and
  - clear the amount bounds with a brief notice when display currency changes.

  Silently reinterpreting 100 USD as 100 EUR is surprising, while preserving the same matching set is mathematically impossible using one converted bound.

  There is also an independent stored-amount invariant to settle. The domain documentation says amounts are positive and type carries debit/credit direction, but persistence and import validation do not consistently enforce positivity. I would enforce nonnegative
  magnitude at every write boundary and in the database, then remove defensive Math.abs behavior after migrating existing negative data.

  Finally, the rate loader currently fetches daily history from 2000 onward for every currency used by any transaction /workspace/budget-analyzer-web/src/hooks/useCurrencies.ts:120. At scale, rate data may cost more than the 10,000 transactions. Deriving the
  actual transaction date range—or eventually adding a Currency Service batch endpoint for the distinct currency/date pairs—would preserve client-side conversion with less data.

  ## What web guidance says about pagination

  Yes, general API guidance says to paginate:

  - Google AIP-158 (https://google.aip.dev/158) says arbitrary, growing collections should provide pagination from the outset.
  - Spotify paginates playlist items (https://developer.spotify.com/documentation/web-api/reference/get-playlists-items), caps an add request at 100 items, and uses snapshot_id
    (https://developer.spotify.com/documentation/web-api/concepts/playlists#version-control-and-snapshots) for concurrency.

  - web.dev’s list virtualization guidance (https://web.dev/articles/virtualize-long-lists-react-window) emphasizes rendering only the visible subset.
  - RFC 9110 If-Match (https://www.rfc-editor.org/rfc/rfc9110.html#name-if-match) describes conditional mutations for preventing lost updates.
  - PostgreSQL collection guidance (https://www.postgresql.org/docs/current/arrays.html#ARRAYS-SEARCHING) recommends a separate membership table rather than identifier arrays.

  But transport pagination and decision ownership are orthogonal. The frontend can still load every transaction and make every interactive decision locally while hydrating the cache through cursor-paged responses.

  At 10,000 transactions, keeping the existing single response is defensible for now:

  - production NGINX already compresses JSON;
  - client pagination limits rendered DOM rows;
  - the user scope is inherently bounded compared with cross-user search.

  Memoization does not address network transfer, JSON parsing, browser memory, or rate-series volume, however. I would establish a supported envelope—perhaps 25,000 or 50,000 transactions—and measure cold-load payload size, parse time, memory, and filter/sort
  latency on representative mobile hardware.

  The natural later evolution is not backend filtering; it is a sync protocol:

  initial cursor-paged hydration
  + collection revision
  + incremental updates
  + soft-delete tombstones

  That still leaves the complete active collection in the browser. Because changing the existing top-level array into a paginated envelope would be breaking, this should be a new endpoint/version when needed.

  ## Disposition of the issue

  I would split the current issue into four narrower decisions:

  1. Replace dynamic saved views with static ID membership.
  2. Define one frontend display-amount contract.
  3. Clarify that backend administrative search uses native-currency amounts and cannot meaningfully sort mixed currencies as one economic amount.
  4. Establish the supported all-transactions loading/synchronization envelope.

  The existing source-assisted plan should be discarded, not repaired. “Create from source” becomes ordinary creation from the currently visible ID set, with no source lineage or pin reconciliation.

  For existing views, exact historical reconstruction is impossible—the system did not retain the displayed result or display currency. The most honest migration is to freeze each view’s effective visible membership at cutover. That preserves what its unfiltered
  detail page shows immediately before migration, though not necessarily what the user originally saw when creating it.

  No files were changed during this review.