# Fuzzy Description Duplicate Detection Plan

## Status

Phases 1 through 7 are complete. The service now has a description-free
duplicate candidate key, a description match result model, a deterministic
description matcher with normalization plus conservative normalized Levenshtein
scoring, and a repository lookup that retrieves active owner-scoped candidates
without requiring description equality. Preview marking now uses that candidate
lookup plus fuzzy description matching. Batch import now re-checks duplicates
through the same candidate and fuzzy matcher flow before persistence. Focused
unit and repository integration coverage now exercises candidate key
normalization, description matching, preview marking, and batch import
duplicate decisions. Documentation now describes the strict financial identity
candidate lookup, fuzzy description comparison, advisory preview flags, batch
re-checking, and `allowDuplicate=true` override behavior.

## Problem

Duplicate detection previously required an exact description match. That missed
transactions that are clearly the same financial event when two statement
formats render the merchant description differently.

Observed example from local data:

- Yearly summary row: `X CORP. PAID FEATURES BASTROP     TX`
- Monthly statement row: `X CORP. PAID FEATURESBASTROPTX`

Both rows have the same owner, blank account ID, bank name, date, amount, type,
and currency. They differ only by description rendering and source file
metadata.

The original exact-description behavior was implemented through
`TransactionDuplicateKey`, `TransactionRepository.findExistingDuplicateKeys`,
preview duplicate marking, and batch import duplicate checks.

## Target Behavior

Duplicate detection should remain strict for the financial identity fields and
fuzzy only for descriptions.

Strict candidate fields:

- `ownerId`
- `accountId`, with empty and `null` treated equivalently
- `bankName`
- `date`
- `amount`, canonicalized to scale 2
- `type`
- `currencyIsoCode`

Description matching should be fuzzy after light normalization. Normalization is
only preprocessing; it is not the duplicate rule by itself.

Preview should mark likely fuzzy matches with existing duplicate metadata so the
user can review and override the result in the UI. Batch import should use the
same duplicate decision so preview and persistence do not drift.

No API response shape change is required for the first implementation. The
existing `duplicate=true` and `duplicateReason=EXISTING_TRANSACTION` fields are
enough. A future API can expose match confidence if the UI needs it.

## Matching Decision

Use a two-stage match:

1. Query exact-field candidates from the database, excluding description from
   the lookup key.
2. Score the incoming description against candidate descriptions in Java.

Initial description preprocessing:

- Trim leading and trailing whitespace.
- Normalize Unicode to a stable comparable form.
- Convert to uppercase with `Locale.ROOT`.
- Collapse whitespace and punctuation noise so parser layout differences do not
  dominate the score.

Initial fuzzy scoring recommendation:

- Use a real similarity metric, such as Jaro-Winkler or normalized Levenshtein.
- Start with a conservative threshold, for example `0.90` or `0.92`.
- Require a minimum normalized description length before fuzzy matching.
- Treat normalized exact equality as a score of `1.0`, but do not require
  equality.

Avoid a PostgreSQL `pg_trgm` dependency for the first pass. The exact candidate
set should be small because amount/date/type/currency/bank/account are already
fixed, and Java scoring is easier to test and tune.

## Implementation Phases

### Phase 1: Extract Duplicate Matching Model

Status: Complete.

Create a service-layer duplicate matching model that separates exact candidate
identity from description comparison.

Tasks:

- Introduce a candidate key that excludes description. Done via
  `TransactionDuplicateCandidateKey`.
- Keep the existing full exact key behavior available only where tests need to
  prove old assumptions, or replace it cleanly if no callers need it. Done;
  `TransactionDuplicateKey` remains available for exact-description key tests
  and legacy repository coverage, but import paths use the candidate matcher.
- Add a small description match result type. Done via
  `TransactionDescriptionMatchResult`, containing:
  - matched or not matched
  - similarity score
  - matching candidate identifier or description, if useful for debugging
- Keep all matching classes in `service/` or `service/dto/`; do not introduce
  `api` dependencies. Done.

Acceptance criteria:

- Candidate identity still distinguishes different account, bank, date, amount,
  type, and currency values.
- Empty and `null` account IDs remain equivalent.
- Existing amount scale behavior remains unchanged.

### Phase 2: Add Description Normalization And Similarity Scoring

Status: Complete.

Implemented description preprocessing and a normalized Levenshtein similarity
metric in `TransactionDescriptionMatcher`.

Tasks:

- Add a dedicated description matcher class with deterministic normalization.
  Done via `TransactionDescriptionMatcher`.
- Choose and document the threshold constant in code. Done; fuzzy matches
  require a normalized Levenshtein similarity score of `0.90` or higher.
- Add a minimum-length guard to avoid over-matching tiny merchant strings. Done;
  fuzzy matching requires both normalized descriptions to contain at least `8`
  comparable characters.
- Prefer a well-tested library if one is already available or cheap to add;
  otherwise implement a small normalized Levenshtein scorer with focused tests.
  Done with an in-service scorer to avoid adding a dependency for this narrow
  phase.

Acceptance criteria:

- The X Corp yearly/monthly descriptions match. Covered by
  `TransactionDescriptionMatcherTest`.
- Punctuation and whitespace-only variants match. Covered by
  `TransactionDescriptionMatcherTest`.
- Case-only variants match. Covered by `TransactionDescriptionMatcherTest`.
- Clearly different descriptions with the same date and amount do not match.
  Covered by `TransactionDescriptionMatcherTest`.
- Very short descriptions do not fuzzy-match unless they are normalized exact
  matches. Covered by `TransactionDescriptionMatcherTest`.

### Phase 3: Change Repository Candidate Lookup

Status: Complete.

Change duplicate lookup to retrieve exact-field candidates without requiring
description equality in SQL.

Tasks:

- Add a repository method that accepts candidate keys and owner ID, then returns
  active matching transaction candidates with descriptions. Done via
  `TransactionRepository.findDuplicateCandidates(...)`.
- Keep soft-deleted rows excluded. Done.
- Keep lookup owner-scoped. Done.
- Avoid broad scans by preserving an index that starts with owner/deleted and
  the exact candidate fields. Done.
- Add a Flyway migration if the current duplicate index needs replacement. Done
  via `V17__replace_duplicate_candidate_index.sql`.

Acceptance criteria:

- Repository integration tests prove candidates are returned for matching exact
  fields even when descriptions differ.
- Different account, bank, currency, type, amount, date, or owner values do not
  return candidates.
- Deleted transactions do not return candidates.

### Phase 4: Wire Preview Duplicate Marking

Status: Complete.

Update preview duplicate marking to use exact-field candidate lookup plus fuzzy
description scoring.

Tasks:

- Replace the exact description key check in preview with the shared duplicate
  matcher. Done.
- Preserve `PreviewDuplicateReason.EXISTING_TRANSACTION` for database matches.
  Done.
- Preserve `PreviewDuplicateReason.IN_BATCH` for duplicates within the same
  preview payload. Done.
- Use the same fuzzy description matcher for in-preview duplicates. Done.

Acceptance criteria:

- A monthly Capital One transaction previews as duplicate when a yearly summary
  row already exists with the same exact fields and a similar description.
- A row with same date and amount but clearly different description does not
  preview as duplicate.
- In-batch fuzzy duplicates mark only later rows as `IN_BATCH`.

### Phase 5: Wire Batch Import Duplicate Re-check

Status: Complete.

Update batch import to use the same duplicate matcher as preview.

Tasks:

- Replace exact description key duplicate checks in `TransactionService` with
  the shared candidate and fuzzy matcher flow. Done.
- Preserve `allowDuplicate=true` behavior. Done.
- Preserve the current "all rows skipped as duplicates" failure behavior.
  Done.
- Keep source file tracking behavior unchanged. Done.

Acceptance criteria:

- A fuzzy duplicate submitted with `allowDuplicate=false` is skipped.
- The same row submitted with `allowDuplicate=true` is imported and counted in
  `duplicatesImported`.
- Preview and batch make the same duplicate decision for the same database
  state.

### Phase 6: Test Coverage

Status: Complete.

Add focused tests around matching behavior and the import paths.

Tasks:

- Unit-test candidate key normalization. Done via
  `TransactionDuplicateCandidateKeyTest`.
- Unit-test description normalization and similarity scoring. Done via
  `TransactionDescriptionMatcherTest`.
- Unit-test preview duplicate marking for exact, fuzzy, non-match, and in-batch
  cases. Done via `TransactionImportServiceTest`.
- Unit-test batch import skip/import behavior for fuzzy duplicates. Done via
  `TransactionServiceTest`.
- Add repository integration tests for candidate lookup. Done via
  `TransactionRepositoryIntegrationTest`.

Acceptance criteria:

- Tests cover the observed X Corp yearly/monthly case.
- Tests cover false-positive guards with same date and amount but different
  merchant descriptions.
- Existing exact duplicate tests are either preserved or intentionally updated
  to the new candidate plus fuzzy semantics.

### Phase 7: Documentation Updates

Status: Complete.

Update documentation in the same implementation change.

Tasks:

- Update `docs/statement-import.md` duplicate detection language. Done.
- Update `docs/database-schema.md` duplicate index and lookup description. Done.
- Correct schema discovery examples if they still refer to a schema that does
  not exist in the local database. Done.
- Update OpenAPI descriptions if they mention exact-description matching. Done.

Acceptance criteria:

- Documentation says duplicate detection is exact on financial identity fields
  and fuzzy on description.
- Documentation still explains that preview flags are advisory and batch import
  re-checks before persistence.
- Documentation explains that users can override duplicate handling with
  `allowDuplicate=true`.

## Non-Goals

- Do not fuzzy-match date or amount.
- Do not merge or delete existing duplicate rows automatically.
- Do not expose a new API field for confidence in the first implementation.
- Do not introduce database-specific fuzzy matching extensions in the first
  implementation.

## Open Tuning Questions

- Final similarity metric and threshold.
- Exact minimum normalized description length.
- Whether `bankName` should remain exact forever or eventually normalize known
  aliases.
- Whether future preview responses should include duplicate confidence or a
  matched existing transaction ID for richer UI review.
