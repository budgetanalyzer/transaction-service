# Run-2 Review Remediation and Standards Alignment Plan

Remediate the multi-file preview and batch-import issues identified while comparing `run-1` and
`run-2`, preserve the safer behavior already present in `run-2`, and bring the code added or
materially changed by `run-2` into alignment with service-common validation, error-handling,
naming, and testing standards. The work keeps the grouped API and the combined atomic persistence
design selected from `run-2`; it does not reopen the branch-selection decision.

## Phase 1: Correct Nested Request Validation and Empty-Group Semantics

### Workspace

.

### Goal

Make the grouped batch request reject null file and transaction elements as stable 400 validation
errors while allowing an individual verified file group to submit an empty transaction array and
deferring the all-empty outcome to the aggregate business rule.

### Scope

- Add container-element validation to both levels of the grouped batch request.
- Keep the top-level `files` collection required and non-empty.
- Change each file's `transactions` collection from required-and-non-empty to required but
  optionally empty.
- Cover null file entries, null transaction entries, a successful mixed empty/non-empty request,
  and an aggregate all-empty request.
- Align the generated OpenAPI schema with the corrected request contract.

### Non-goals

- Allowing a null `files` collection, an empty top-level `files` collection, or a null per-file
  `transactions` collection.
- Adding redundant null guards in the controller or service after Bean Validation has established
  the request invariant.
- Changing row-level field constraints or duplicate-detection behavior.
- Changing the grouped response shape.

### Required context

- Read `AGENTS.md` and `../service-common/docs/code-quality-standards.md` before changing Java.
- Read the Bean Validation and stable error-contract guidance in
  `../service-common/docs/testing-patterns.md` and `../service-common/docs/error-handling.md`.
- Review `BatchImportRequest`, `BatchImportFileRequest`, `BatchImportTransactionRequest`, and
  `TransactionController.batchImportTransactions(...)` together so validation completes before
  any controller dereference.
- Review the current empty-import handling in `TransactionService.batchImport(...)` and
  `rejectEmptyImport(...)`; the service already owns the aggregate
  `BATCH_IMPORT_NO_TRANSACTIONS_CREATED` rule.
- Review the grouped request assertions in `TransactionOpenApiIntegrationTest` before changing
  schema annotations.
- Confirm `gradle/libs.versions.toml` still selects service-common `0.0.15`. The review baseline
  states that this artifact is already published to Maven Local. If it cannot be resolved, stop
  and report the prerequisite rather than switching repositories from this phase.

### Execution steps

1. Change `BatchImportRequest.files` to validate every element with type-use `@NotNull` and
   `@Valid` constraints while retaining the collection-level `@NotEmpty` constraint. Supply an
   element-specific validation message so a null entry is reported at its indexed `files[...]`
   path.
2. Change `BatchImportFileRequest.transactions` from `@NotEmpty` to collection-level `@NotNull`,
   and add type-use `@NotNull` plus `@Valid` constraints to each transaction element. Keep the
   request-to-service conversion direct; do not add service-layer validation for a condition that
   the validated API boundary makes impossible.
3. Update the `@Schema` descriptions and required metadata on the two collections. The top-level
   file list remains `minItems: 1`; the per-file transaction list remains required but must no
   longer advertise `minItems: 1`.
4. Add controller tests that POST `{"files":[null]}` and a file whose
   `transactions` value is `[null]`. Assert only the stable contract: 400 status,
   `VALIDATION_ERROR`, the indexed field path, and no token verification or service invocation.
5. Port the useful empty-group coverage from `run-1`: prove an empty per-file transaction array
   passes request validation and reaches token verification/service mapping, and prove a request
   whose aggregate creates nothing returns 422 with
   `BATCH_IMPORT_NO_TRANSACTIONS_CREATED` rather than 400.
6. Add or refine service/integration coverage for an empty file group accompanying a successful
   non-empty group. Assert ordered per-file results, zero counts and no provenance for the empty
   group, successful provenance for the non-empty group, and unchanged atomic behavior.
7. Update `TransactionOpenApiIntegrationTest` to require `files` and
   `files[].transactions`, retain `files.minItems == 1`, and verify the per-file transaction array
   no longer carries a non-empty constraint.

### Implementation notes

- Prefer declarations equivalent to
  `List<@NotNull @Valid BatchImportFileRequest>` and
  `List<@NotNull @Valid BatchImportTransactionRequest>` so null collection elements cannot pass
  cascade validation.
- Bean Validation ignores null values for `@Valid` alone. The element-level `@NotNull` constraints
  are what prevent the controller's `file.transactions()` and conversion dereferences from
  becoming 500 responses.
- An empty file group is not the same as an empty grouped request. It retains its verified source
  and ordered response position, but creates no transaction and no `FileImport` provenance.
- The service must reject only when the aggregate accepted transaction count is zero. Preserve the
  existing business error code and 422 mapping for that outcome.
- Use camelCase test method names from the moment new tests are added; Phase 3 performs the broader
  cleanup of names already introduced on `run-2`.

### Validation

Run the repository-required commands in sequence and inspect the output for compiler and
Checkstyle warnings as well as failures:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Confirm the completed tests demonstrate both invalid indexed-null payloads, both permitted
empty-group scenarios, and the corrected OpenAPI `minItems` contract.

### Completion criteria

- Null file and transaction elements produce indexed 400 `VALIDATION_ERROR` responses without
  controller or service dereferences.
- `files` is still required and non-empty, and each `transactions` collection is required.
- Empty per-file transaction arrays pass request validation.
- A mixed empty/non-empty request can succeed with ordered zero/non-zero per-file results, while an
  aggregate all-empty request returns coded 422.
- The clean format/build sequence passes without warnings.

## Phase 2: Restore Coded Business Errors and Preserve Parser Causes

### Workspace

.

### Goal

Classify mixed verified-token identities as a coded business invariant and retain the caught parser
exception as the immediate cause when adding safe per-file preview context.

### Scope

- Restore the `BATCH_IMPORT_SOURCE_MISMATCH` business error code present on `run-1`.
- Return 422 `APPLICATION_ERROR` for statement-format or account mismatches after every token has
  been verified.
- Preserve the complete parser exception chain whether the caught `BusinessException` has a nested
  cause or not.
- Retain `run-2`'s safe generic I/O failure message and original `IOException` cause.
- Cover the stable HTTP error contract and exception-cause identity.

### Non-goals

- Rejecting different parser revision IDs when statement format and account match.
- Moving token verification into persistence code or allowing persistence before all tokens are
  verified.
- Exposing storage paths, infrastructure exception messages, file contents, hashes, or stack
  traces to clients.
- Copying `run-1`'s unsafe `IOException.getMessage()` behavior into `run-2`.
- Changing token cryptography, ownership verification, or expiry handling.

### Required context

- Read `../service-common/docs/error-handling.md`, especially the distinction between malformed
  400 input and coded 422 business invariants.
- Review `TransactionController.validateBatchSourceIdentities(...)`, `BudgetAnalyzerError`, and the
  two current source-mismatch controller tests.
- Review `TransactionImportService.parseFile(...)`, `readFileContent(...)`, parser failure creation
  in the extractor registry, and the parser/read-failure tests in
  `TransactionImportServiceTest`.
- Treat `run-1` as a semantic reference only for the source-mismatch code and status. Preserve
  `run-2`'s cleaner request conversion, combined save, and safe file-read error behavior.

### Execution steps

1. Add `BATCH_IMPORT_SOURCE_MISMATCH` to `BudgetAnalyzerError` with a schema description explaining
   that verified grouped tokens must share one statement format and account.
2. Change `TransactionController.validateBatchSourceIdentities(...)` to throw
   `BusinessException` with that code when either shared identity component differs. Keep the
   current verify-all-tokens-before-validate ordering and continue accepting different parser
   revisions.
3. Update both mismatch controller tests to assert 422 status, `APPLICATION_ERROR`, and
   `BATCH_IMPORT_SOURCE_MISMATCH`; assert all tokens were verified and the transaction service was
   never called. Do not assert human-readable message text.
4. Add the source-mismatch case to the batch endpoint's documented 422 OpenAPI examples or
   description so the machine-readable code is discoverable beside the no-transactions-created
   error.
5. In `TransactionImportService.parseFile(...)`, pass the caught `BusinessException` itself as the
   new wrapper's cause instead of passing `businessException.getCause()`. Preserve the original
   error code and safe filename-bearing outer context.
6. Update parser failure tests to cover a parser `BusinessException` with no nested cause and prove
   that it becomes the wrapper's immediate cause. Retain or add a nested-cause case that proves the
   original nested exception remains reachable through the caught parser exception.
7. Keep `readFileContent(...)`'s generic client-facing message and direct `IOException` cause.
   Adjust tests only to assert stable error code/type and cause identity, never the I/O exception's
   message or other internal text.

### Implementation notes

- The JSON structure and each token are individually valid in a source-mismatch request. The
  cross-token shared-identity requirement is therefore a business invariant, not malformed input.
- Construct `BusinessException` using the repository's established message/code/cause parameter
  order. The HTTP contract depends on the code; tests must not lock in wording.
- Wrapping the caught `BusinessException` preserves its own stack and any nested parser cause.
  Passing only `getCause()` loses all parser context when that value is null.
- Finding 5 from the branch review requires no production change on `run-2`; it is a regression
  constraint. The current filename-bearing generic file-read error is the behavior to preserve.

### Validation

Run the required sequence and inspect all output for warnings:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Verify the mismatch tests cover both format and account differences, and that parser tests cover
both absent and present nested causes.

### Completion criteria

- Both shared-identity mismatch variants return coded 422 responses after all token verification
  and before service persistence.
- Different parser revisions under one shared format/account remain accepted.
- The per-file parser wrapper's immediate cause is the caught parser `BusinessException`, with its
  nested cause preserved when present.
- File-read failures keep a safe generic client message and retain the `IOException` internally.
- The clean format/build sequence passes without warnings.

## Phase 3: Align Run-2 Code and Tests With Service-Common Quality Rules

### Workspace

.

### Goal

Remove standards violations introduced or retained in the run-2 change area without altering the
selected multi-file behavior.

### Scope

- Rename the 36 test methods added by the `main...run-2` diff from underscore-separated names to
  clear camelCase behavior names.
- Clean the modified batch-import captor test so it needs no unchecked suppression.
- Remove message-text assertions added by `run-2` and retain stable contract assertions.
- Correct abbreviated production/test field names in `TransactionImportService`.
- Apply small readability and KISS cleanups in the duplicate-description and transaction mapping
  code already changed on `run-2`.

### Non-goals

- Mechanically renaming the 382 underscore-named test methods already present on `main`; that is
  separate repository-wide debt, not part of the run-2 regression surface.
- Removing unrelated pre-existing `@SuppressWarnings` annotations elsewhere in the test suite.
- Replacing the repository's established unit, MockMvc, or Testcontainers test layers.
- Changing normalized-equality rules, duplicate precedence, persistence batching, or provenance.
- Introducing any new suppression annotation.

### Required context

- Read the test naming, stable exception-contract, `var`, class-field naming, and suppression rules
  in `../service-common/docs/testing-patterns.md` and
  `../service-common/docs/code-quality-standards.md`.
- Use `git diff --unified=0 main...run-2 -- src/test/java` to identify the 36 added underscore-named
  methods rather than expanding the task to every historical test.
- Review the existing typed `argThat`/`AtomicReference` precedent adjacent to the raw list captor in
  `TransactionControllerTest`.
- Review `TransactionImportService`, `TransactionImportServiceTest`,
  `TransactionDescriptionMatcher`, `TransactionDescriptionMatcherTest`, and
  `TransactionService.mapToEntity(...)` before applying naming-only edits.

### Execution steps

1. Rename every underscore-named test method added by the run-2 diff in
   `TransactionControllerTest`, `TransactionDescriptionMatcherTest`,
   `TransactionDuplicateMatcherTest`, `TransactionImportServiceTest`, and
   `TransactionServiceTest`. Use one consistent camelCase form that states behavior and condition,
   preferably `shouldBehaviorWhenCondition`.
2. Also rename the materially changed `batchImport_allowDuplicate_mapsFlagToServiceDto` test to a
   camelCase name that describes API-to-service mapping without obsolete `Dto` terminology.
3. Replace `ArgumentCaptor<List<BatchImportFile>>` created from raw `List.class` with a type-safe
   verification technique, using the nearby `argThat`/captured-reference pattern if appropriate.
   Remove the method's `@SuppressWarnings("unchecked")`; do not move or replace the suppression.
4. Remove the four message assertions added by run-2: the preview controller `$.message` assertion
   and the three `BusinessException.getMessage()` assertions in
   `TransactionImportServiceTest`. Assert status, type, code, indexed field path where applicable,
   cause identity, and absence of downstream interactions instead.
5. Rename the class-level `StatementExtractorRegistry extractorRegistry` field and matching
   constructor parameter/mock field to `statementExtractorRegistry`, updating all references in
   production and the changed tests.
6. Rename the `PreviewTransaction dto` parameter in `TransactionService.mapToEntity(...)` to
   `previewTransaction` and update the associated Javadoc/comment wording so the full type meaning
   remains visible with local `var` usage.
7. Rename the boolean predicate `TransactionDescriptionMatcher.match(...)` to `matches(...)` and
   remove the redundant non-spacing-mark branch after confirming `Character.isLetterOrDigit(...)`
   already excludes Unicode mark categories. Keep the existing normalization behavior and exact
   match/non-match coverage unchanged.
8. Audit only the run-2-added Java lines for wildcard/Hibernate imports, forbidden `*Dto` class
   suffixes, explicit local types where `var` is possible, abbreviated class fields, new
   suppressions, and stale fuzzy terminology. Fix any remaining violations without broad unrelated
   refactoring.

### Implementation notes

- Test method renames are behavior-neutral and should not add redundant `@DisplayName` values.
- The shared testing standard treats HTTP/exception messages as human-readable and unstable. Error
  status/type/code and field paths are the programmatic contract.
- `@SuppressWarnings` is not an acceptable way to hide an avoidable generic capture warning.
- Class-level fields use the full class name even in tests. Local short names remain acceptable only
  when their scope is genuinely small and clear.
- Preserve normalized Unicode equality. The cleanup to `isComparableCodePoint(...)` must be backed
  by the existing diacritic, punctuation, numeric, and merely-similar description tests.

### Validation

Run static diff checks against the complete working tree before the required build:

```bash
if git diff --unified=0 main -- src/test/java | \
  rg -q '^\+  void [A-Za-z0-9]+_'; then exit 1; fi
if git diff --unified=0 main -- src/test/java | \
  rg -q '^\+.*(@SuppressWarnings|jsonPath\("\$\.message|getMessage\(\))'; then exit 1; fi
```

Then run the repository-required sequence and inspect it for compiler and Checkstyle warnings:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

### Completion criteria

- No test method added by the final `main`-to-working-tree diff uses underscores.
- The changed generic list verification compiles without an unchecked suppression or warning.
- No run-2-added test asserts human-readable exception message text.
- Changed class fields and mapping parameters follow full-name conventions.
- Description normalization remains behaviorally unchanged after the predicate cleanup.
- No wildcard imports, Hibernate imports, forbidden `*Dto`-suffixed classes, new suppressions, or
  stale fuzzy terminology remain in the run-2-added code.
- The clean format/build sequence passes without warnings.

## Phase 4: Reconcile Documentation and Perform Final Verification

### Workspace

.

### Goal

Make the canonical transaction-service guidance accurately describe the corrected request and
error contracts, then verify the complete run-2 remediation as one merge-ready branch.

### Scope

- Update the nearest API, import workflow, duplicate behavior, and repository guidance affected by
  the fixes.
- Document permitted empty per-file transaction arrays and aggregate all-empty failure semantics.
- Document coded 422 source mismatches and accepted parser-revision differences.
- Preserve the safe file-read error statement and parser-cause behavior without exposing internals.
- Run final source, documentation, formatting, static-analysis, coverage, and test checks.

### Non-goals

- Editing sibling repositories or frontend behavior.
- Rewriting historical release plans as canonical documentation.
- Adding a database migration, changing service-common, or changing artifact versions.
- Expanding the final cleanup to unrelated repository-wide test naming/message debt inherited from
  `main`.

### Required context

- Review the final implementation and tests from Phases 1-3 before documenting behavior.
- Review `AGENTS.md`, `docs/api/README.md`, `docs/statement-import.md`, and
  `docs/duplicate-detection.md`. Use discovery searches rather than assuming the current line
  references are exhaustive.
- Review `TransactionController` OpenAPI annotations and
  `TransactionOpenApiIntegrationTest` so generated and prose documentation agree.
- Preserve `docs/configuration.md` unless the implementation actually changes configuration; no
  upload-size or token setting change is planned.

### Execution steps

1. Update both `BatchImportRequest` descriptions in `docs/api/README.md` so `files` remains
   required/non-empty, `files[].transactions` is required but may be empty, null elements are
   invalid, and the service returns `BATCH_IMPORT_NO_TRANSACTIONS_CREATED` only when the aggregate
   request creates nothing.
2. Update the batch workflow and endpoint sections in `docs/statement-import.md` with the same
   distinction. Include a concise mixed empty/non-empty example or explanatory note, and state that
   an empty group creates no provenance.
3. Update `docs/duplicate-detection.md` to make clear that a source group may contain zero reviewed
   rows, preserves its ordered result when another group succeeds, and is governed by the existing
   aggregate empty-import rule.
4. Document statement-format/account mismatch as 422 `APPLICATION_ERROR` with code
   `BATCH_IMPORT_SOURCE_MISMATCH` in the API/import error guidance. Continue to state that different
   parser revision IDs are valid under one shared identity.
5. Review `AGENTS.md` quick references and adjust them only where needed to expose the empty-group
   and coded source-mismatch semantics to future implementers. Keep its non-empty accepted-group
   provenance statement because that statement remains correct.
6. Search canonical code/docs, excluding historical plans where appropriate, for stale claims that
   every per-file transaction array is non-empty or that source mismatch is an uncoded 400. Resolve
   every contradiction in the affected workflow.
7. Inspect `git diff --check` and the complete `main`-to-working-tree diff for accidental changes, weakened
   tests, unsafe message exposure, or documentation drift. Confirm no file outside
   transaction-service was modified.
8. Run the required format/build sequence one final time. Read the full output and treat compiler,
   Checkstyle, Spotless, Javadoc, coverage, and test-result warnings or failures as blockers. The
   environment-dependent JDK 25 class-sharing and dynamic-agent notices emitted by Mockito/Byte
   Buddy test instrumentation are pre-existing tooling diagnostics outside this remediation scope
   and do not block completion.

### Implementation notes

- Canonical docs should describe the current behavior, while this plan records release-specific
  remediation context. Do not create a maintained inventory of individual test names.
- Use stable error codes in examples and avoid promising exact message wording as a client
  contract.
- Do not claim that an empty source succeeds in isolation: it is accepted by request validation but
  the aggregate service result must still create at least one transaction.
- Documentation changes are part of completion, not follow-up work.

### Validation

Run documentation and source consistency searches, adjusting patterns if wording changed:

```bash
rg -n -i 'transactions.*required.*non[- ]empty|transactions.*non[- ]empty|source mismatch|same statement format|same.*account' \
  AGENTS.md docs/api/README.md docs/statement-import.md docs/duplicate-detection.md \
  src/main/java
git diff --check
```

Then run the required final sequence:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

### Completion criteria

- Canonical API/import/duplicate documentation agrees that per-file transaction arrays are
  required but may be empty and that only the aggregate empty outcome is a coded 422 failure.
- Canonical documentation and OpenAPI describe source identity mismatch as
  `BATCH_IMPORT_SOURCE_MISMATCH` with 422 semantics and continue to allow parser-revision variance.
- Safe I/O error behavior remains documented without exposing internal exception details.
- The final diff contains all review remediations and scoped standards improvements, with no
  sibling-repository writes or unrelated behavior changes.
- `git diff --check` and the required clean format/build sequence pass with no errors or in-scope
  warnings; pre-existing JDK 25 Mockito/Byte Buddy instrumentation notices are exempt.
