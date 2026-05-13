# Saved View Criteria Alignment Plan

## Problem

Saved views do not preserve all filters a user can apply on the Transactions
screen. The clearest bug is transaction type: a user can filter the table to
`DEBIT`, save the view, and later see `CREDIT` transactions in that view.

The root cause is contract drift:

- Transaction search uses `TransactionFilter`.
- Saved views use separate `ViewCriteria` and `ViewCriteriaApi` objects.
- The frontend has its own `ViewCriteriaApi` type and manually builds saved
  view criteria from UI filter state.

These separate models are no longer aligned.

## Current Gaps

Normal transaction filtering supports or exposes these fields:

- Date range: `dateFrom`, `dateTo`
- Text search / description
- Bank name
- Account ID
- Transaction type: `DEBIT`, `CREDIT`
- Amount range: `minAmount`, `maxAmount`

Saved views currently persist:

- `startDate`, `endDate`
- `accountIds`
- `bankNames`
- `currencyIsoCodes`
- `minAmount`, `maxAmount`
- `searchText`

Saved views currently do not persist `type`.

Saved views also have misleading multi-value fields. `accountIds`,
`bankNames`, and `currencyIsoCodes` are modeled as sets, but
`SavedViewService.criteriaToFilter(...)` only uses the first value from each
set. That makes the API look more capable than the behavior.

Text search also differs. The Transactions screen search matches description
or bank name, while saved view `searchText` is mapped to transaction
description only.

## Target Contract

Saved views should support every normal user-facing transaction filter:

- `dateFrom`
- `dateTo`
- `searchText`
- `bankNames`
- `accountIds`
- `currencyIsoCodes`
- `minAmount`
- `maxAmount`
- `type`

Use `dateFrom` and `dateTo` directly. No backward compatibility is required
for the existing `startDate` and `endDate` saved-view criteria fields.

Keep these transaction search fields out of normal saved views for now:

- `ownerId`: normal saved views are already scoped by authenticated user.
- `id`: pinned transactions are the better model for fixed transaction
  membership.
- `createdAfter`, `createdBefore`, `updatedAfter`, `updatedBefore`: these are
  not normal user-facing transaction filters today.

Admin cross-user saved searches can be considered later as a separate feature
because they have different authorization and ownership semantics.

## Recommended Architecture

Keep `ViewCriteria` separate, but stop duplicating query behavior.

Use three distinct shapes:

1. `TransactionFilter`: HTTP query binding for transaction search endpoints.
2. `ViewCriteria`: saved-view API and persistence contract.
3. A shared internal query criteria model for repository/specification
   construction.

`TransactionFilter` and `ViewCriteria` should both map into the shared internal
criteria model. The repository/specification layer should build predicates from
that internal model rather than from controller DTOs or saved-view DTOs.

This preserves a clean saved-view contract while giving transaction search and
saved views one implementation of filtering semantics.

## Criteria Object Decision

Keep `ViewCriteria` as persisted domain state and `ViewCriteriaApi` as the
saved-view API DTO, but make the contract intentionally mirror user-facing
transaction filters.

Add explicit conversion from both `TransactionFilter` and `ViewCriteria` to a
shared internal criteria model. Build repository specifications from that
internal criteria model.

Benefits:

- Keeps persisted saved-view JSON independent from Spring MVC request binding.
- Lets saved views intentionally exclude admin-only or non-user-facing filters.
- Allows proper multi-value semantics for bank/account/currency.
- Makes the API honest: plural fields really match multiple values.
- Avoids maintaining two separate predicate builders for the same transaction
  fields.

## Backend Plan

1. Change `ViewCriteria`:
   - Replace `startDate` with `dateFrom`.
   - Replace `endDate` with `dateTo`.
   - Add `TransactionType type`.
   - Keep plural fields for bank/account/currency if multi-value matching is
     implemented.
   - Keep `searchText` as the text field name because the intended behavior is
     broader than description-only matching.

2. Change `ViewCriteriaApi`:
   - Match the new `ViewCriteria` field names and fields.
   - Include OpenAPI schema descriptions for `type`.
   - Remove `startDate` and `endDate`.

3. Add a shared internal criteria record, for example
   `service.dto.TransactionCriteria`:
   - `Long id`
   - `String ownerId`
   - `Set<String> accountIds`
   - `Set<String> bankNames`
   - `LocalDate dateFrom`
   - `LocalDate dateTo`
   - `Set<String> currencyIsoCodes`
   - `BigDecimal minAmount`
   - `BigDecimal maxAmount`
   - `TransactionType type`
   - `String searchText`
   - `Instant createdAfter`
   - `Instant createdBefore`
   - `Instant updatedAfter`
   - `Instant updatedBefore`

4. Add explicit mappers:
   - `TransactionFilter` maps to internal criteria with singleton sets for
     `accountId`, `bankName`, and `currencyIsoCode`.
   - `ViewCriteria` maps to internal criteria with plural sets preserved and
     the authenticated user's `ownerId` injected by `SavedViewService`.
   - Do not allow normal saved-view JSON to supply `ownerId`.

5. Update `TransactionSpecifications`:
   - Build specifications from the shared internal criteria model.
   - Keep a compatibility overload for `TransactionFilter` if needed, but make
     it delegate through the mapper.
   - Match `bankNames`, `accountIds`, and `currencyIsoCodes` with real `IN` or
     OR predicates.
   - Apply `type` as an exact enum predicate.
   - Apply `dateFrom`, `dateTo`, `minAmount`, and `maxAmount` as range
     predicates.
   - Apply timestamp predicates for transaction search, even though normal
     saved views do not expose timestamp filters.

6. Align text search:
   - Use `searchText` for saved views.
   - Match the Transactions screen behavior: text search matches description
     or bank name.
   - Make transaction search decide whether its existing `description`
     parameter should remain description-only or map into the same `searchText`
     behavior. If changed, document it as a search behavior change.

7. Remove saved-view conversion through single-value `TransactionFilter`:
   - Replace `SavedViewService.criteriaToFilter(...)` with mapping to the
     shared internal criteria.
   - This is where the current bank name weirdness is fixed.

8. Update tests:
   - Saved view with `type=DEBIT` excludes `CREDIT`.
   - Saved view with multiple `bankNames` includes matching transactions from
     all listed banks.
   - Saved view with multiple `accountIds` includes all listed accounts.
   - Saved view with multiple `currencyIsoCodes` includes all listed
     currencies.
   - Date fields use `dateFrom` and `dateTo`.
   - Saved-view `searchText` matches description or bank name.
   - Transaction search still applies supported timestamp filters.

9. Update documentation:
   - `docs/api/README.md`
   - Any saved-view examples in `docs/`
   - OpenAPI-generated or source annotations as needed.

## Frontend Plan

1. Change frontend `ViewCriteriaApi`:
   - Replace `startDate` and `endDate` with `dateFrom` and `dateTo`.
   - Add `type?: TransactionType`.

2. Update Transactions page saved-view builder:
   - Include `typeFilter`.
   - Use `dateFrom` and `dateTo`.
   - Continue including bank/account/amount/search filters.

3. Update saved-view summaries:
   - Show transaction type in `CreateViewModal`.
   - Show transaction type in `EditViewModal`.
   - Show transaction type in `ViewCriteriaSummary`.
   - Rename date labels to match the new contract where relevant.

4. Decide currency UX:
   - If currency is part of saved-view criteria, expose it as a normal
     Transactions screen filter.
   - If it is not user-facing, remove it from saved-view criteria for now.

5. Add frontend tests:
   - Saving a debit-filtered Transactions screen sends `criteria.type =
     "DEBIT"`.
   - Date criteria uses `dateFrom` and `dateTo`.
   - Summaries render type criteria.

## Open Questions

1. Should transaction search's existing `description` parameter remain
   description-only, or should it be renamed/extended to the same broad
   `searchText` behavior as saved views and the Transactions screen?

2. Should currency be added to the Transactions screen as a filter before it is
   retained in saved-view criteria?

3. Should saved views support multiple selected banks/accounts/currencies in
   the UI, or should the backend use plural fields only to keep the API ready
   for that future UI?

## Phased Implementation Plan

Each phase below is sized for one focused agent session. Do not combine phases
unless the preceding phase is already merged and verified. Before any phase
that changes Java code, read
`../service-common/docs/code-quality-standards.md` first.

### Phase 1: Backend saved-view contract alignment

Goal: fix the debit/credit saved-view bug and align date field names without
changing the repository query architecture yet.

Implementation steps:

1. Update `ViewCriteria`:
   - Rename `startDate` to `dateFrom`.
   - Rename `endDate` to `dateTo`.
   - Add `TransactionType type`.
   - Update `empty()` and all constructor call sites.
2. Update `ViewCriteriaApi`:
   - Replace `startDate` and `endDate` with `dateFrom` and `dateTo`.
   - Add `type` with `CREDIT` / `DEBIT` OpenAPI schema metadata.
   - Update `toDomain()` and `from(...)`.
3. Update `SavedViewService.criteriaToFilter(...)`:
   - Use `criteria.dateFrom()` and `criteria.dateTo()`.
   - Preserve the existing open-ended end-date behavior.
   - Pass `criteria.type()` into the `TransactionFilter`.
   - Leave the existing first-value behavior for `accountIds`, `bankNames`,
     and `currencyIsoCodes`; that is fixed in Phase 3.
4. Update backend tests:
   - Existing saved-view tests compile with the renamed date fields.
   - Add or update a saved-view test proving `type=DEBIT` excludes `CREDIT`.
   - Add or update a saved-view test proving `dateFrom` and `dateTo` are mapped
     into transaction filtering.
5. Update documentation:
   - Update saved-view criteria fields in `docs/api/README.md`.
   - Update any other saved-view criteria examples under `docs/`.

Verification:

- Run the focused saved-view service/controller tests.
- Run `./gradlew spotlessApply` if formatting changes are made.
- Run `./gradlew clean build` before closing the phase, publishing
  `service-common` to Maven Local first if dependency resolution fails.

Exit criteria:

- Saved views can persist and return `criteria.type`.
- Saved views use `dateFrom` and `dateTo` in request/response bodies.
- The known debit/credit mismatch is fixed.
- Multi-value saved-view fields are still documented as pending alignment.

### Phase 2: Shared internal transaction criteria

Goal: create one internal model for transaction query semantics while keeping
observable behavior unchanged except for the Phase 1 saved-view fixes.

Implementation steps:

1. Add `service.dto.TransactionCriteria` with fields for:
   - `id`
   - `ownerId`
   - `accountIds`
   - `bankNames`
   - `dateFrom`
   - `dateTo`
   - `currencyIsoCodes`
   - `minAmount`
   - `maxAmount`
   - `type`
   - `searchText`
   - `createdAfter`
   - `createdBefore`
   - `updatedAfter`
   - `updatedBefore`
2. Add explicit mapper methods:
   - From `TransactionFilter` to `TransactionCriteria`, converting single
     `accountId`, `bankName`, and `currencyIsoCode` values into singleton
     sets.
   - From `ViewCriteria` plus `ownerId` and `openEnded` to
     `TransactionCriteria`, preserving saved-view sets.
3. Update `TransactionSpecifications`:
   - Add `withCriteria(TransactionCriteria criteria)`.
   - Make existing `withFilter(TransactionFilter filter)` delegate through the
     mapper for compatibility.
   - Keep the existing text predicate behavior unchanged in this phase:
     transaction search `description` and saved-view `searchText` both still
     target transaction descriptions only. Phase 4 decides whether text search
     should broaden to description-or-bank-name matching.
4. Update `SavedViewService`:
   - Replace saved-view filtering through `TransactionFilter` with
     `TransactionCriteria`.
   - Remove or deprecate `criteriaToFilter(...)` once tests no longer need it.
5. Update tests:
   - Add focused mapper tests for `TransactionFilter` and `ViewCriteria`.
   - Add specification tests or service tests proving transaction search still
     applies `type`, date, amount, timestamp, and owner filters.
   - Keep Phase 1 saved-view tests passing.
6. Update documentation:
   - Update this plan's status notes if a status section is added.
   - Update API docs only if observable request/response behavior changes.

Verification:

- Run focused mapper/specification/service tests.
- Run `./gradlew spotlessApply`.
- Run `./gradlew clean build`.

Exit criteria:

- Transaction search and saved views share the same internal criteria model.
- The primary specification builder no longer depends directly on controller
  request DTOs.
- Compatibility for existing transaction search query parameters is preserved.

### Phase 3: Real multi-value saved-view semantics

Goal: make plural saved-view criteria fields honest by matching every supplied
bank, account, and currency value.

Implementation steps:

1. Update `TransactionSpecifications.withCriteria(...)`:
   - Match `accountIds` with an OR / `IN` predicate across the provided set.
   - Match `bankNames` with an OR / `IN` predicate across the provided set.
   - Match `currencyIsoCodes` with an OR / `IN` predicate across the provided
     set.
   - Preserve the current case sensitivity contract intentionally:
     case-insensitive LIKE for account and bank if matching existing
     transaction search semantics, and case-insensitive exact match for
     currency.
2. Normalize mapper behavior:
   - Drop null, blank, or empty set values before building predicates.
   - Ensure singleton values from `TransactionFilter` still behave exactly as
     they did before Phase 3.
3. Update saved-view tests:
   - Multiple `bankNames` include transactions from all listed banks.
   - Multiple `accountIds` include transactions from all listed accounts.
   - Multiple `currencyIsoCodes` include transactions from all listed
     currencies.
   - Blank or empty set entries do not create broad or invalid predicates.
4. Update documentation:
   - Document that saved-view `bankNames`, `accountIds`, and
     `currencyIsoCodes` accept multiple values.
   - Remove any Phase 1 note saying multi-value semantics are pending.

Verification:

- Run focused specification and saved-view tests.
- Run `./gradlew spotlessApply`.
- Run `./gradlew clean build`.

Exit criteria:

- Saved-view plural fields all apply as multi-value filters.
- No saved-view path silently uses only the first value.

### Phase 4: Text-search decision and alignment

Goal: resolve the remaining search-text mismatch explicitly instead of folding
it into the structural refactor.

Implementation steps:

1. Decide the contract:
   - Option A: keep transaction search `description` description-only and make
     saved-view `searchText` description-only.
   - Option B: introduce or document broad `searchText` behavior that matches
     description or bank name, matching the Transactions screen.
2. Implement the chosen predicate behavior in `TransactionSpecifications`.
3. If Option B is chosen:
   - Add a query parameter only if needed by the public API.
   - Keep `description` backward-compatible or clearly document any behavior
     change.
4. Update tests:
   - Saved-view `searchText` matches the documented fields.
   - Transaction search `description` or `searchText` matches the documented
     fields.
   - Multi-word search behavior remains covered.
5. Update documentation:
   - `docs/api/README.md`.
   - Source OpenAPI annotations for affected request fields.

Verification:

- Run focused search/specification tests.
- Run `./gradlew spotlessApply`.
- Run `./gradlew clean build`.

Exit criteria:

- The saved-view text-search contract and transaction-search text contract are
  both explicit and tested.
- No code comments or docs still describe conflicting text-search behavior.

### Phase 5: Frontend saved-view alignment

Goal: update the web client to send, display, and edit the backend contract.
This phase lives in the frontend repository, not `transaction-service`.

Implementation steps:

1. In the frontend repository, update `ViewCriteriaApi`:
   - Replace `startDate` and `endDate` with `dateFrom` and `dateTo`.
   - Add optional `type`.
2. Update the Transactions page saved-view builder:
   - Include the current type filter when creating or updating a saved view.
   - Send `dateFrom` and `dateTo`.
   - Continue sending bank, account, amount, currency, and search criteria that
     are supported by the UI.
3. Update saved-view display and edit surfaces:
   - Show transaction type in create, edit, and criteria summary components.
   - Rename date field labels and bindings to the new contract.
   - If currency is not user-facing, either expose it as a normal filter or
     stop sending it until the UX exists.
4. Update frontend tests:
   - Saving a debit-filtered table sends `criteria.type = "DEBIT"`.
   - Saved date criteria uses `dateFrom` and `dateTo`.
   - Summaries render type criteria.
5. Update frontend documentation if that repository has saved-view API or usage
   docs.

Verification:

- Run the focused frontend test suite.
- Run the repository's formatter/linter.
- Manually exercise create/edit saved view flows against the updated backend
  if both services can be run locally.

Exit criteria:

- The frontend no longer sends `startDate` or `endDate`.
- The frontend preserves the user's transaction type filter when saving views.
- Saved-view summaries show the same criteria the backend persists.

## Suggested Session Order

1. Phase 1 first, because it fixes the user-visible debit/credit bug with the
   smallest backend change.
2. Phase 2 second, because it creates the shared internal query model without
   taking on multi-value predicate changes at the same time.
3. Phase 3 third, because it changes query semantics for plural saved-view
   fields and needs separate review.
4. Phase 4 after Phase 3, because text-search behavior is a product/API
   decision rather than a prerequisite for the debit/credit fix.
5. Phase 5 after the backend contract is stable, or in parallel with Phase 1
   only if the frontend is pinned to the Phase 1 backend contract.
