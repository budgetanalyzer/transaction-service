# Active Saved-View Override Counts Plan

Make saved-view `pinnedCount` and `excludedCount` report only active, owner-owned transaction overrides, using the same resolution pass that determines effective membership and `transactionCount`. This corrects stale response metadata after soft deletion while preserving ID-based override storage and the intended behavior that a replacement transaction with a new ID can dynamically re-enter an open-ended view.

## Phase 1: Centralize Saved-View Resolution And Active Override Counts

### Goal

Introduce one service-layer saved-view resolution result that exposes effective membership, effective transaction count, and counts of active pinned and excluded IDs without adding repository queries or changing membership behavior.

### Scope

- Read `/workspace/service-common/docs/code-quality-standards.md` before modifying Java and `/workspace/service-common/docs/testing-patterns.md` before modifying tests.
- Add a service-layer DTO, such as `SavedViewResolution`, containing:
  - the existing `ViewMembership`;
  - the number of active, owner-owned stored pinned IDs;
  - the number of active, owner-owned stored excluded IDs;
  - a derived effective transaction count equal to `matched.size() + pinned.size()`.
- Refactor `SavedViewService` so a single resolution path:
  - loads active transactions matching the saved criteria;
  - resolves the union of stored pin and exclusion IDs through the existing owner-scoped active-ID query;
  - constructs `matched`, `pinned`, and `excluded` membership groups with their current semantics;
  - captures active pin and exclusion counts before any membership presentation rules hide pins already represented in `matched`.
- Keep `getViewTransactions(...)` behavior unchanged by delegating to the centralized resolution and returning its membership.
- Keep `countViewTransactions(...)` temporarily compatible by delegating to the centralized resolution's transaction count. Remove it only in a later phase if all callers are migrated and no compatibility value remains.
- Add focused service unit tests for the new resolution values.

### Non-goals

- Do not remove stale IDs from `saved_view.pinned_ids` or `saved_view.excluded_ids` during transaction deletion.
- Do not scan or update saved views from single or bulk transaction-delete operations.
- Do not normalize override sets into relational join tables.
- Do not change open-ended date resolution, criteria matching, pin/exclusion precedence, ownership rules, or API response shapes.

### Required context

- `AGENTS.md`, especially soft-delete, layering, documentation, prerequisite, and build instructions.
- `docs/saved-views.md` for effective membership and active-ID semantics.
- `src/main/java/org/budgetanalyzer/transaction/service/SavedViewService.java` for the existing membership pipeline.
- `src/main/java/org/budgetanalyzer/transaction/service/dto/ViewMembership.java` for current membership presentation semantics.
- `src/main/java/org/budgetanalyzer/transaction/domain/SavedView.java` for stored override sets.
- `src/main/java/org/budgetanalyzer/transaction/repository/TransactionRepository.java` for the owner-scoped active-ID query.
- `/workspace/service-common/service-core/src/main/java/org/budgetanalyzer/core/repository/SoftDeleteOperations.java` for active transaction query semantics.

### Implementation notes

- Place the new result in `service/dto`; do not introduce a new `service -> api` dependency.
- Reuse `resolveStoredMembershipIds(...)`. It already performs the authoritative active-and-owned filtering for both override sets with one query.
- Define `activePinnedCount` from the resolved stored pin set, not from `ViewMembership.pinned()`. A stored active pin that also matches the criteria appears only in `matched`, but it remains an active pin for the `pinnedCount` metadata.
- Define `activeExcludedCount` from the resolved stored exclusion set. Preserve the current rule that the `excluded` membership group includes active exclusions even when a transaction does not currently match the criteria.
- Preserve query complexity: one criteria query plus, when overrides exist, one active override-ID query per resolved view.
- Do not add speculative null handling for persistence values that existing converters and database constraints require to be non-null.

### Validation

- Extend `SavedViewServiceTest` to verify:
  - active stored pins and exclusions are counted;
  - soft-deleted or missing override IDs are not counted;
  - foreign-owner override IDs are not counted;
  - a pin that also matches criteria remains included in `activePinnedCount` even though it is absent from `ViewMembership.pinned()`;
  - effective transaction count remains `matched + pinned`.
- Run:

```bash
./gradlew test \
  --tests org.budgetanalyzer.transaction.service.SavedViewServiceTest \
  --tests org.budgetanalyzer.transaction.service.dto.TransactionCriteriaTest
```

- If service-common artifacts cannot be resolved, follow `AGENTS.md`: build and publish `/workspace/service-common` to Maven Local, then rerun the targeted tests.

### Completion criteria

- Saved-view resolution exposes effective membership and active override counts from one consistent service operation.
- Existing membership and transaction-count behavior remains unchanged.
- Unit tests cover active, deleted, missing, foreign-owned, and matching-pin cases.
- Targeted tests pass.

## Phase 2: Use Effective Counts In API Responses And Add Delete/Re-import Regression Coverage

### Goal

Replace raw persisted-set sizes in every `SavedViewResponse` with active resolved counts, prove the delete-and-replacement scenario, and document the observable contract.

### Scope

- Update `SavedViewResponse.from(...)` to accept resolved `transactionCount`, `pinnedCount`, and `excludedCount` values rather than reading raw override-set sizes from `SavedView`.
- Add a controller-local response mapping helper that resolves each `SavedView` once and builds its response from that single result.
- Migrate create, list, get, update, pin, unpin, exclude, and unexclude response paths to the helper.
- Keep `GET /v1/views/{id}/transactions` response semantics unchanged.
- Update affected controller tests and add regression coverage for API-visible active counts.
- Add an integration regression proving that deleting an excluded transaction and creating a replacement transaction with a new generated ID allows the replacement to appear in criteria membership while the old exclusion no longer contributes to `excludedCount`.
- Update the nearest saved-view documentation in the same phase.

### Non-goals

- Do not automatically transfer an exclusion from a deleted transaction to a re-imported transaction based on date, amount, description, file import, or another fingerprint.
- Do not restore soft-deleted transaction rows or reuse their IDs during import.
- Do not change file-import duplicate detection or file-import history behavior.
- Do not purge inactive override IDs from persistence as part of response rendering.
- Do not change API field names or add a database migration.

### Required context

- The completed Phase 1 resolution result and tests.
- `src/main/java/org/budgetanalyzer/transaction/api/SavedViewController.java` for every response-producing endpoint.
- `src/main/java/org/budgetanalyzer/transaction/api/response/SavedViewResponse.java` for current raw count mapping.
- `src/test/java/org/budgetanalyzer/transaction/api/SavedViewControllerAuthorizationTest.java` for mocked controller dependencies and response paths.
- `src/test/java/org/budgetanalyzer/transaction/service/SavedViewServiceIntegrationTest.java` for database-backed saved-view membership fixtures.
- `src/main/java/org/budgetanalyzer/transaction/service/TransactionService.java` and `src/main/java/org/budgetanalyzer/transaction/domain/Transaction.java` for soft deletion and generated transaction IDs.
- `docs/saved-views.md`, `docs/api/README.md`, and `docs/database-schema.md` for saved-view count and storage documentation.

### Implementation notes

- The controller helper should call the Phase 1 resolver only once per view. Avoid separately resolving `transactionCount`, `pinnedCount`, and `excludedCount`.
- `SavedViewResponse` should remain an API-layer mapping type. Pass scalar resolved values into it rather than making service code depend on API response types.
- `pinnedCount` means active, owner-owned stored pins, including pins whose transactions also match criteria.
- `excludedCount` means active, owner-owned stored exclusions, including active exclusions that do not currently match criteria.
- `transactionCount` continues to mean effective visible membership: `matched + pinned`, after active exclusions are removed.
- The persisted JSON arrays may retain inactive historical IDs. Make clear in documentation that storage size is not the API count.
- Check all call sites before removing `countViewTransactions(...)`; retain a delegating method if tests or legitimate service consumers still use it.
- Correct misleading saved-view count wording encountered in the nearest documentation, including any statement that implies raw stored override-array sizes are returned.

### Validation

- Extend or add controller/response tests proving `SavedViewResponse` uses supplied active counts rather than `SavedView.getPinnedIds().size()` and `getExcludedIds().size()`.
- Add database-backed regression coverage with this sequence:
  1. create an open-ended view with a lower date bound and no upper date bound;
  2. create a matching transaction and exclude its generated ID;
  3. soft-delete that transaction;
  4. create an equivalent active replacement and confirm it receives a different generated ID;
  5. resolve the view and assert the replacement is in `matched`, the old ID is absent from effective `excluded`, and active `excludedCount` is zero.
- Verify an active exclusion still reports `excludedCount=1` and remains absent from `matched`.
- Run targeted tests:

```bash
./gradlew test \
  --tests org.budgetanalyzer.transaction.service.SavedViewServiceTest \
  --tests org.budgetanalyzer.transaction.service.SavedViewServiceIntegrationTest \
  --tests org.budgetanalyzer.transaction.api.SavedViewControllerAuthorizationTest
```

- Run formatting and the full build:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

- If service-common artifacts cannot be resolved, follow the documented Maven Local publication procedure before retrying.
- Review `git diff --check` and verify documentation matches the implemented response semantics.

### Completion criteria

- No `SavedViewResponse` count is derived directly from raw persisted pin or exclusion set sizes.
- All response-producing saved-view endpoints use one consistent resolution per view.
- Soft-deleted and foreign-owned override IDs do not contribute to API counts.
- Active pin and exclusion count semantics remain correct when criteria membership overlaps.
- The delete-and-replacement regression passes and demonstrates that the replacement ID dynamically re-enters the open-ended view.
- Saved-view API and storage documentation describe active counts versus retained historical IDs accurately.
- Targeted tests, formatting checks, and the full build pass.
