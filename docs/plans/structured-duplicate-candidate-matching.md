# Structured Duplicate Candidate Matching Refactor Plan

## Status

In progress. Phases 1 and 2 are complete. Phase 2 also completed the
overlapping duplicate matcher wiring described in phase 4; the encoded SQL
rewrite remains pending in phase 3.

This plan refactors duplicate candidate lookup to use structured field matching
instead of encoded string keys in repository SQL. The target behavior is
unchanged: duplicate detection remains strict on financial identity fields and
uses service-layer normalized exact or conservative fuzzy matching for
descriptions.

## Problem

`TransactionRepository.findDuplicateCandidates(...)` currently accepts
structured repository criteria, then adapts them to the same length-prefixed
lookup value that the legacy PostgreSQL query builds internally. The native
query uses `OCTET_LENGTH(CONVERT_TO(..., 'UTF8'))` so SQL byte counts match the
repository adapter's Java UTF-8 byte counts.

That encoding is correct for the current key design, but it has drawbacks:

- It duplicates Java key-construction rules in SQL.
- It makes the repository query hard to read and maintain.
- It is PostgreSQL-specific.
- It obscures the real predicate, which is equality across structured
  financial identity fields.
- It keeps old exact-key machinery visible even though fuzzy duplicate
  detection only needs description-free candidate matching.

Column lengths do not remove the need for the current encoding. Length limits
cap stored values, but they do not make string concatenation unambiguous, do
not distinguish null markers from literal values, and do not address embedded
separator characters. The cleaner fix is to stop concatenating fields for
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

Tasks:

- Replace the `OCTET_LENGTH(CONVERT_TO(...))` query in
  `findDuplicateCandidates(...)` with a structured predicate.
- Prefer a PostgreSQL CTE that expands candidate values and joins on fields,
  for example with typed arrays and `unnest(...)`.
- Match account IDs with empty-string/null equivalence on both sides.
- Return the existing projection fields:
  - `candidateKey` or a structured equivalent needed for grouping
  - `transactionId`
  - `description`
- Keep the existing duplicate-candidate index on owner, deleted, account, bank,
  date, amount, type, and currency.

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

Tasks:

- Update repository integration tests for structured candidate lookup:
  - same financial fields with different description returns a candidate
  - different account, bank, date, amount, type, or currency does not match
  - empty account ID matches null account ID
  - different owner does not match
  - deleted transaction does not match
  - non-ASCII and embedded separator values still match by structured fields
- Update service tests to assert repository calls use structured candidates
  where useful.
- Remove tests that exist only to verify encoded-key collision avoidance if the
  encoded key is gone.

Acceptance criteria:

- Existing fuzzy duplicate detection behavior is covered at service and
  repository levels.
- Tests no longer require SQL byte-length key encoding to pass.

### Phase 6: Documentation

Tasks:

- Update `docs/database-schema.md` to describe structured duplicate candidate
  lookup instead of encoded lookup keys.
- Verify `docs/statement-import.md` still accurately describes strict
  financial identity matching plus service-layer fuzzy description matching.
- Update the completed fuzzy duplicate plans only if they contain stale
  implementation details that would mislead future maintainers.

Acceptance criteria:

- Documentation describes behavior and the current implementation accurately.
- No follow-up documentation work is left for the refactor.

### Phase 7: Verification

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

Avoid replacing the current encoding with simple concatenation. That would make
the SQL shorter but would weaken correctness around nulls, literal marker
values, embedded separators, and Unicode.
