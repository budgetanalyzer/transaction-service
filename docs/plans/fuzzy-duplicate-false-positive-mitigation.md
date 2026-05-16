# Fuzzy Duplicate False Positive Mitigation Plan

## Status

Phases 1, 2, and 3 complete. Phase 4 remains planned.

## Context

The current duplicate detection flow already uses normalized Levenshtein
similarity only for transaction descriptions. It does not score the full
duplicate key.

The matching flow is:

1. Build a strict duplicate candidate bucket from financial identity fields:
   `accountId`, `bankName`, `date`, `amount`, `type`, and `currencyIsoCode`.
2. Retrieve persisted candidates in the same owner scope for those exact
   financial identity fields.
3. Compare only the incoming description and candidate description with
   `TransactionDescriptionMatcher`.

The false-positive risk comes from description-only fuzzy scoring after
normalization. Numeric identifiers remain in the normalized description, so a
single changed digit in a long description can still produce a high normalized
Levenshtein score. For example, `TRANSFER 1234567890` and
`TRANSFER 1234567891` differ by one character after normalization and can pass
the current `0.90` threshold even though the numeric reference may distinguish
separate financial events.

Batch import has a separate issue: rows skipped because they match an existing
persisted transaction are currently added to the in-batch seen set before the
skip happens. Because fuzzy matching is not transitive, a later row can be
skipped due only to similarity with a row that was never imported.

## Target Behavior

Duplicate detection should remain exact on financial identity fields and fuzzy
only on descriptions, but fuzzy description matches must be conservative around
numeric identifiers.

Expected behavior:

- Normalized exact description equality still matches immediately.
- Fuzzy description matching remains available for layout, punctuation,
  whitespace, and minor spelling variations.
- Descriptions that contain numeric reference tokens should fuzzy-match only
  when the numeric token lists match exactly.
- Rows skipped as persisted duplicates during batch import should not become
  in-batch duplicate candidates for later rows.
- Rows accepted for creation, including rows with `allowDuplicate=true`, should
  become in-batch duplicate candidates for later rows.

## Phase 1: Guard Numeric Tokens Before Fuzzy Matches

Update `TransactionDescriptionMatcher` so fuzzy matching requires exact numeric
token compatibility before calculating or accepting a Levenshtein score.

Proposed matching order:

1. Normalize both descriptions using the existing normalization path.
2. If the normalized descriptions are equal, return a match with score `1.0`.
3. If either original description contains numeric tokens, extract each
   contiguous digit sequence from both descriptions and require the ordered
   token lists to be exactly equal.
4. If numeric token lists differ, return no match.
5. Apply the existing minimum normalized description length guard.
6. Calculate normalized Levenshtein similarity and accept only scores at or
   above the existing threshold.

Status: Complete. `TransactionDescriptionMatcher` now extracts ordered
contiguous digit tokens from the original descriptions after the normalized
exact-match check and before fuzzy scoring. Fuzzy matches are blocked unless
both descriptions have no numeric tokens or their numeric token lists match
exactly.

Examples:

- `Whole-Foods Market #123` vs `Whole Foods Market 123`: match.
- `X CORP. PAID FEATURES BASTROP TX` vs `X CORP. PAID FEATURESBASTROPTX`:
  match.
- `TRANSFER 1234567890` vs `TRANSFER 1234567891`: no match.
- `CHECK 1045` vs `CHECK 1046`: no match.
- `PAYPAL DIGITAL SERVICES` vs `PAYPAL DIGITAL SERVICE`: match, because no
  numeric tokens are involved.

Do not use a threshold increase as the primary mitigation. Raising the
threshold may reduce some false positives, but long descriptions with one
changed reference digit can still score highly. Exact numeric-token agreement
addresses the failure mode directly.

## Phase 2: Track Only Accepted Rows As In-Batch Candidates

Update `TransactionService.batchImport(...)` so
`seenTransactionsByCandidateKey` contains only rows accepted for creation.

Proposed batch filtering order for each row:

1. Build the candidate key.
2. Check for duplicates against persisted candidates.
3. Check for duplicates against already accepted in-batch rows for the same
   candidate key.
4. If the row is a duplicate and `allowDuplicate=false`, increment
   `duplicatesSkipped` and continue without adding it to the seen set.
5. If the row is a duplicate and `allowDuplicate=true`, increment
   `duplicatesImported`.
6. Map the row to an entity, add it to `toCreate`, and then add the source row
   to `seenTransactionsByCandidateKey`.

This preserves the current behavior where accepted in-batch duplicates affect
later rows, while preventing skipped persisted duplicates from affecting later
rows.

Status: Complete. `TransactionService.batchImport(...)` now checks persisted
and already accepted in-batch candidates before deciding whether to skip a row.
Rows skipped because `allowDuplicate=false` are not added to
`seenTransactionsByCandidateKey`. Rows accepted for creation, including rows
accepted through `allowDuplicate=true`, are added after they are mapped for
persistence so they remain candidates for later rows in the same request.

## Phase 3: Test Coverage

Add focused tests for the matcher and batch filtering behavior.

Status: Complete. `TransactionDescriptionMatcherTest` covers numeric-token
guards for one-digit differences, punctuation and whitespace differences,
matching numeric references with fuzzy description differences, ordered
multiple-token matching, no-token fuzzy matching, and existing normalization
guards. `TransactionServiceTest` covers skipped persisted duplicates staying
out of the in-batch seen set, accepted rows becoming in-batch candidates, and
`allowDuplicate=true` rows remaining candidates for later duplicate checks.

`TransactionDescriptionMatcherTest` coverage:

- A one-digit difference in a long numeric reference does not match.
- The same numeric reference with punctuation or whitespace differences still
  matches.
- Multiple numeric tokens must match in order.
- Descriptions without numeric tokens continue to use fuzzy scoring.
- Existing normalized exact, punctuation, whitespace, case, diacritic, and short
  description tests remain valid.

`TransactionServiceTest` coverage:

- A row skipped because it matches an existing persisted transaction is not
  added to the seen set.
- A later row that does not match persisted candidates imports even if it would
  fuzzy-match the skipped row.
- An accepted row is still added to the seen set and can cause a later in-batch
  duplicate to be skipped.
- A row imported with `allowDuplicate=true` is treated as accepted and can
  affect later in-batch duplicate checks.

## Phase 4: Documentation Updates

Update the affected duplicate-detection documentation in the same change:

- `docs/plans/fuzzy-description-duplicate-detection.md`: note this mitigation
  plan as a follow-up to the completed fuzzy duplicate work.
- `docs/statement-import.md`: explain that fuzzy description matching requires
  exact numeric-token agreement when numeric references are present.
- `docs/database-schema.md`: no schema change is expected, but verify duplicate
  candidate lookup language still says description comparison happens in Java
  after exact financial candidate lookup.

## Acceptance Criteria

- Levenshtein similarity is still calculated only between normalized
  descriptions.
- Numeric/reference token differences prevent fuzzy duplicate matches.
- The observed X Corp yearly/monthly description variant still matches.
- Persisted duplicate rows skipped during batch import do not become in-batch
  duplicate candidates.
- Accepted batch rows, including duplicate overrides, remain in-batch duplicate
  candidates for later rows.
- Focused unit tests cover both review issues.
- Documentation is updated in the same implementation change.

## Non-Goals

- Do not fuzzy-match financial identity fields.
- Do not remove the existing `allowDuplicate=true` override.
- Do not expose duplicate confidence or matched transaction IDs in the API.
- Do not introduce database fuzzy matching extensions.
- Do not change file import tracking semantics.
