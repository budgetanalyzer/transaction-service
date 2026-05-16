# Structured Duplicate Candidate Matching Refactor Plan

## Status

Complete. Phases 1 through 7 are complete. Phase 2 also completed the
overlapping duplicate matcher wiring described in phase 4. The repository SQL
now expands structured candidate fields with a PostgreSQL `unnest(...)` CTE and
joins on the financial identity columns directly.

This plan refactors duplicate candidate lookup to use structured field matching
instead of encoded string keys in repository SQL. The target behavior is
unchanged: duplicate detection remains strict on financial identity fields and
uses service-layer normalized exact or conservative fuzzy matching for
descriptions.

## Problem

`TransactionRepository.findDuplicateCandidates(...)` previously accepted
structured repository criteria, then adapted them to the same length-prefixed
lookup value that the legacy PostgreSQL query built internally. The native query
used `OCTET_LENGTH(CONVERT_TO(..., 'UTF8'))` so SQL byte counts matched the
repository adapter's Java UTF-8 byte counts.

That encoding was correct for the old key design, but it had drawbacks:

- It duplicated Java key-construction rules in SQL.
- It made the repository query hard to read and maintain.
- It was PostgreSQL-specific.
- It obscured the real predicate, which is equality across structured
  financial identity fields.
- It kept old exact-key machinery visible even though fuzzy duplicate
  detection only needs description-free candidate matching.

Column lengths did not remove the need for unambiguous matching. Length limits
cap stored values, but they did not make string concatenation unambiguous, did
not distinguish null markers from literal values, and did not address embedded
separator characters. The cleaner fix was to stop concatenating fields for
repository matching.

## Target Behavior

Preserve the existing duplicate candidate semantics:

- Scope lookup to the authenticated owner.
- Exclude soft-deleted transactions.
- Match `accountId`, `bankName`, `date`, `amount`, `type`, and
  `currencyIsoCode` exactly.
- Treat empty `accountId` and `null` as equivalent.
- Compare amounts at scale 2.
- Exclude description from the database candidate predicate.
- Return candidate transaction ID and description so Java can apply normalized
  exact or conservative fuzzy description matching.

No API response shape or user-visible duplicate behavior should change.

## Implementation Phases

### Phase 1: Remove Unused Exact-Key Repository Lookup

Status: Complete.

Tasks:

- Remove `TransactionRepository.findExistingDuplicateKeys(...)`. Done.
- Remove repository integration tests that cover only
  `findExistingDuplicateKeys(...)`. Done.
- Search for remaining production uses of `TransactionDuplicateKey`. Done; no
  production path needed it.
- Delete `TransactionDuplicateKey` and its tests if they are left only as
  legacy exact-key coverage. Done.

Acceptance criteria:

- No production code references `findExistingDuplicateKeys(...)`.
- No tests assert behavior for an unused repository method.
- Any remaining exact-key type has a current production purpose.

### Phase 2: Introduce Structured Repository Lookup Inputs

Status: Complete.

Tasks:

- Keep `TransactionDuplicateCandidateKey` as the service-layer normalized
  financial identity object. Done.
- Replace repository calls that pass encoded `String` keys with structured
  candidate values. Done.
- Use a repository-facing input shape that carries:
  - `accountId`
  - `bankName`
  - `date`
  - `amount`
  - `type`
  - `currencyIsoCode`
  Done via `TransactionDuplicateCandidateCriteria`.
- Preserve amount canonicalization in `TransactionDuplicateCandidateKey`. Done.
- Preserve null and empty account normalization before repository lookup. Done.

Acceptance criteria:

- `TransactionDuplicateMatcher` groups candidates by
  `TransactionDuplicateCandidateKey`, not encoded string values. Done.
- `TransactionDuplicateCandidateKey.toLookupValue()` is deleted if no longer
  needed, or retained only for clearly current tests or compatibility. Done;
  legacy lookup encoding is isolated in `TransactionDuplicateCandidateCriteria`
  until phase 3 replaces the SQL.

### Phase 3: Replace Encoded SQL With Field-Level Matching

Status: Complete.

Tasks:

- Replace the `OCTET_LENGTH(CONVERT_TO(...))` query in
  `findDuplicateCandidates(...)` with a structured predicate. Done.
- Prefer a PostgreSQL CTE that expands candidate values and joins on fields,
  for example with typed arrays and `unnest(...)`. Done.
- Match account IDs with empty-string/null equivalence on both sides. Done.
- Return the existing projection fields:
  - `candidateKey` or a structured equivalent needed for grouping
  - `transactionId`
  - `description`
  Done via `StructuredTransactionDuplicateCandidate`.
- Keep the existing duplicate-candidate index on owner, deleted, account, bank,
  date, amount, type, and currency. Done.

Acceptance criteria:

- The repository query no longer constructs length-prefixed encoded keys.
- Candidate lookup still uses the existing composite duplicate-candidate index
  shape.
- Description matching remains entirely in the service layer.

### Phase 4: Update Duplicate Matcher Wiring

Status: Complete.

Tasks:

- Replace `candidateLookupValue(...)` with a method that returns
  `TransactionDuplicateCandidateKey`. Done.
- Change `findExistingCandidatesByKey(...)` to pass structured candidates to
  the repository. Done.
- Group returned candidates by the same structured key used for incoming
  preview transactions. Done.
- Keep `matchesExistingTransaction(...)` and `matchesSeenTransaction(...)`
  unchanged unless the projection shape requires a small adapter. Done.

Acceptance criteria:

- Preview duplicate marking and batch duplicate filtering call the same
  structured candidate lookup.
- Fuzzy description matching behavior is unchanged.

### Phase 5: Test Coverage

Status: Complete.

Tasks:

- Update repository integration tests for structured candidate lookup:
  - same financial fields with different description returns a candidate
  - different account, bank, date, amount, type, or currency does not match
  - empty account ID matches null account ID
  - different owner does not match
  - deleted transaction does not match
  - non-ASCII and embedded separator values still match by structured fields
  Done via `TransactionRepositoryIntegrationTest`.
- Update service tests to assert repository calls use structured candidates
  where useful. Done via existing service tests.
- Remove tests that exist only to verify encoded-key collision avoidance if the
  encoded key is gone. Done; no encoded-key collision tests remain.

Acceptance criteria:

- Existing fuzzy duplicate detection behavior is covered at service and
  repository levels.
- Tests no longer require SQL byte-length key encoding to pass.

### Phase 6: Documentation

Status: Complete.

Tasks:

- Update `docs/database-schema.md` to describe structured duplicate candidate
  lookup instead of encoded lookup keys. Done.
- Verify `docs/statement-import.md` still accurately describes strict
  financial identity matching plus service-layer fuzzy description matching.
  Done; no change required.
- Update the completed fuzzy duplicate plans only if they contain stale
  implementation details that would mislead future maintainers. Done; no
  misleading implementation details found.

Acceptance criteria:

- Documentation describes behavior and the current implementation accurately.
- No follow-up documentation work is left for the refactor.

### Phase 7: Verification

Status: Complete.

Run focused tests first:

```bash
./gradlew test --tests '*TransactionRepositoryIntegrationTest'
./gradlew test --tests '*TransactionServiceTest' --tests '*TransactionImportServiceTest'
```

Then run the full build:

```bash
./gradlew clean build
```

If `service-common` artifacts cannot be resolved, publish the sibling
`service-common` repository to Maven Local as documented in `AGENTS.md`, then
retry.

## Tradeoffs

Structured matching makes the repository query easier to understand and removes
the need to mirror Java's byte-length key encoding in SQL. It also aligns the
query with the actual duplicate predicate and keeps fuzzy description logic in
Java where it is easier to test.

The main cost is implementation complexity around passing a collection of
structured candidates into a Spring Data native query. PostgreSQL arrays with
`unnest(...)` are likely the best fit for the current database, but they still
need integration coverage because binding and type inference can be brittle.

Avoid replacing structured field matching with simple concatenation. That would
make the SQL shorter but would weaken correctness around nulls, literal marker
values, embedded separators, and Unicode.
