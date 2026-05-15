# Fuzzy Description Duplicate Detection Plan

## Status

Planned. No implementation has been started.

## Problem

Duplicate detection currently requires an exact description match. That misses
transactions that are clearly the same financial event when two statement
formats render the merchant description differently.

Observed example from local data:

- Yearly summary row: `X CORP. PAID FEATURES BASTROP     TX`
- Monthly statement row: `X CORP. PAID FEATURESBASTROPTX`

Both rows have the same owner, blank account ID, bank name, date, amount, type,
and currency. They differ only by description rendering and source file
metadata.

The current exact-description behavior is implemented through
`TransactionDuplicateKey`, `TransactionRepository.findExistingDuplicateKeys`,
preview duplicate marking, and batch import duplicate checks. The statement
import documentation also describes descriptions as exact matches.

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

Create a service-layer duplicate matching model that separates exact candidate
identity from description comparison.

Tasks:

- Introduce a candidate key that excludes description.
- Keep the existing full exact key behavior available only where tests need to
  prove old assumptions, or replace it cleanly if no callers need it.
- Add a small description match result type containing at least:
  - matched or not matched
  - similarity score
  - matching candidate identifier or description, if useful for debugging
- Keep all matching classes in `service/` or `service/dto/`; do not introduce
  `api` dependencies.

Acceptance criteria:

- Candidate identity still distinguishes different account, bank, date, amount,
  type, and currency values.
- Empty and `null` account IDs remain equivalent.
- Existing amount scale behavior remains unchanged.

### Phase 2: Add Description Normalization And Similarity Scoring

Implement the description preprocessing and similarity metric.

Tasks:

- Add a dedicated description matcher class with deterministic normalization.
- Choose and document the threshold constant in code.
- Add a minimum-length guard to avoid over-matching tiny merchant strings.
- Prefer a well-tested library if one is already available or cheap to add;
  otherwise implement a small normalized Levenshtein scorer with focused tests.

Acceptance criteria:

- The X Corp yearly/monthly descriptions match.
- Punctuation and whitespace-only variants match.
- Case-only variants match.
- Clearly different descriptions with the same date and amount do not match.
- Very short descriptions do not fuzzy-match unless they are normalized exact
  matches.

### Phase 3: Change Repository Candidate Lookup

Change duplicate lookup to retrieve exact-field candidates without requiring
description equality in SQL.

Tasks:

- Add a repository method that accepts candidate keys and owner ID, then returns
  active matching transaction candidates with descriptions.
- Keep soft-deleted rows excluded.
- Keep lookup owner-scoped.
- Avoid broad scans by preserving an index that starts with owner/deleted and
  the exact candidate fields.
- Add a Flyway migration if the current duplicate index needs replacement.

Acceptance criteria:

- Repository integration tests prove candidates are returned for matching exact
  fields even when descriptions differ.
- Different account, bank, currency, type, amount, date, or owner values do not
  return candidates.
- Deleted transactions do not return candidates.

### Phase 4: Wire Preview Duplicate Marking

Update preview duplicate marking to use exact-field candidate lookup plus fuzzy
description scoring.

Tasks:

- Replace the exact description key check in preview with the shared duplicate
  matcher.
- Preserve `PreviewDuplicateReason.EXISTING_TRANSACTION` for database matches.
- Preserve `PreviewDuplicateReason.IN_BATCH` for duplicates within the same
  preview payload.
- Use the same fuzzy description matcher for in-preview duplicates.

Acceptance criteria:

- A monthly Capital One transaction previews as duplicate when a yearly summary
  row already exists with the same exact fields and a similar description.
- A row with same date and amount but clearly different description does not
  preview as duplicate.
- In-batch fuzzy duplicates mark only later rows as `IN_BATCH`.

### Phase 5: Wire Batch Import Duplicate Re-check

Update batch import to use the same duplicate matcher as preview.

Tasks:

- Replace exact description key duplicate checks in `TransactionService` with
  the shared candidate and fuzzy matcher flow.
- Preserve `allowDuplicate=true` behavior.
- Preserve the current "all rows skipped as duplicates" failure behavior.
- Keep source file tracking behavior unchanged.

Acceptance criteria:

- A fuzzy duplicate submitted with `allowDuplicate=false` is skipped.
- The same row submitted with `allowDuplicate=true` is imported and counted in
  `duplicatesImported`.
- Preview and batch make the same duplicate decision for the same database
  state.

### Phase 6: Test Coverage

Add focused tests around matching behavior and the import paths.

Tasks:

- Unit-test candidate key normalization.
- Unit-test description normalization and similarity scoring.
- Unit-test preview duplicate marking for exact, fuzzy, non-match, and in-batch
  cases.
- Unit-test batch import skip/import behavior for fuzzy duplicates.
- Add repository integration tests for candidate lookup.

Acceptance criteria:

- Tests cover the observed X Corp yearly/monthly case.
- Tests cover false-positive guards with same date and amount but different
  merchant descriptions.
- Existing exact duplicate tests are either preserved or intentionally updated
  to the new candidate plus fuzzy semantics.

### Phase 7: Documentation Updates

Update documentation in the same implementation change.

Tasks:

- Update `docs/statement-import.md` duplicate detection language.
- Update `docs/database-schema.md` duplicate index and lookup description.
- Correct schema discovery examples if they still refer to a schema that does
  not exist in the local database.
- Update OpenAPI descriptions if they mention exact-description matching.

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
