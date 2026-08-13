# Phased Multi-File Statement Preview and Import Plan

Extend statement preview and token-backed batch import to process multiple ordered files of one
statement format and account while preserving per-file provenance. Implement the change through
five independently verifiable checkpoints so each fresh AI Session Handler worker inherits a
coherent, focused-test-passing worktree instead of one session spanning every application layer.

The completed workflow must be all-or-nothing, return grouped per-file and aggregate results,
identify the first failing source file through the standard API error contract, and use exact
normalized duplicate matching against persisted transactions and earlier files only. Repeated rows
within one faithful source file remain valid. Execute the phases in order because each phase relies
on the contracts and behavior established by the preceding checkpoint.

## Phase 1: Replace Fuzzy Description Matching With Exact Normalized Matching

### Workspace

.

### Goal

Establish the final description-matching rule independently of the multi-file contract changes:
transactions match only when their strict financial identity matches and their existing normalized
description forms are equal.

### Scope

- Simplify `TransactionDescriptionMatcher.match(...)` to normalized equality.
- Remove Levenshtein thresholds, numeric-token special cases, and fuzzy-only helpers.
- Replace fuzzy-match test expectations with exact normalized match and non-match expectations.
- Keep amount scale canonicalization and the strict bank/date/amount/type/currency candidate
  identity unchanged.
- Update duplicate-detection documentation in the same phase.

### Non-goals

- Changing preview or batch HTTP request and response contracts.
- Changing same-file or cross-file duplicate precedence.
- Changing owner scoping, soft deletion, file hashes, preview tokens, or persistence behavior.
- Adding feature flags or leaving disabled fuzzy-matching code in place.

### Required context

- Read `AGENTS.md` and confirm this phase requires no sibling-repository implementation.
- Before changing Java, read `../service-common/docs/code-quality-standards.md` completely.
- Read `../service-common/docs/testing-patterns.md` for JUnit 5 and AssertJ conventions.
- Review `TransactionDescriptionMatcher.java`, `TransactionDuplicateMatcher.java`, and their direct
  callers and tests before editing.
- Review `docs/duplicate-detection.md` for every statement that describes fuzzy or exact matching.

### Execution steps

1. Reduce `TransactionDescriptionMatcher` to equality of the normalized descriptions already
   produced by its current Unicode and whitespace normalization rules; do not introduce a second
   canonicalization path.
2. Delete fuzzy-only constants and code, including Levenshtein and numeric-token branches, and
   remove or rewrite tests that exist only to exercise those branches.
3. Expand matcher and service-level tests to prove normalized exact descriptions match while
   descriptions that are merely similar no longer match. Preserve tests for strict financial
   identity and owner-scoped persisted candidates.
4. Update `docs/duplicate-detection.md` so the documented production behavior is exact normalized
   description equality with the existing strict financial identity.

### Implementation notes

- This is a behavior simplification, not a new matching abstraction. Reuse the current normalized
  form and delete unused fuzzy machinery.
- A description-only match is insufficient; repository candidate lookup must continue to enforce
  bank, date, canonical amount, type, and currency identity first.
- Do not change `PreviewDuplicateReason` in this phase.

### Validation

Run the focused matcher and service tests:

```bash
./gradlew test --tests '*TransactionDescriptionMatcherTest' \
  --tests '*TransactionServiceTest'
```

Search the code and affected documentation for stale fuzzy-matching terminology:

```bash
rg -n "fuzzy|Levenshtein|similarity threshold|numeric token" \
  src/main/java src/test/java docs/duplicate-detection.md
```

Any remaining occurrence must describe historical behavior explicitly or be removed.

### Completion criteria

- Exact normalized descriptions match and fuzzy-only descriptions do not.
- Fuzzy-only implementation code and tests are gone.
- Strict financial identity, amount canonicalization, owner scoping, and persisted lookup behavior
  are unchanged.
- Focused matcher and service tests pass.
- `docs/duplicate-detection.md` describes the implemented exact-matching rule.

## Phase 2: Deliver the Grouped Multi-File Preview Path

### Workspace

.

### Goal

Change preview into one ordered multi-file operation that produces one token and result per source
file, aborts on the first file failure, and marks duplicates against persisted transactions and
completed earlier files without treating repeated rows in the current file as duplicates.

### Scope

- Replace multipart `file` with a validated, non-empty ordered `files` collection while retaining
  one shared `statementFormatId` and optional `accountId`.
- Make `PreviewResponse` wrap an ordered collection of per-file preview results.
- Add service-layer grouped preview records without `*Dto` suffixes and keep API-to-service mapping
  at the controller boundary.
- Implement one public read-only transactional service operation for the complete preview request.
- Resolve the visible statement format once, then read, hash, check history, select a parser
  revision, extract transactions, and issue a distinct preview token for every file in request
  order.
- Query persisted duplicate candidates once for the grouped preview and apply first-file-wins
  matching against completed earlier files only.
- Update preview controller, authorization, OpenAPI, service, and matcher tests plus the directly
  affected preview/import/configuration documentation.

### Non-goals

- Changing the batch endpoint contract or implementing grouped batch persistence.
- Supporting different statement formats or accounts within one preview request.
- Returning multiple parsing errors; processing stops at the first failed file.
- Comparing rows with other rows from the same source file.
- Creating a combined content hash, preview token, or source identity.
- Raising upload-size limits or changing CSV/PDF wizard endpoints.

### Required context

- Confirm Phase 1 is complete and its focused tests pass.
- Read `AGENTS.md`, then read `../service-common/docs/code-quality-standards.md`,
  `../service-common/docs/error-handling.md`, and
  `../service-common/docs/testing-patterns.md` completely before changing Java.
- Verify no prerequisite requires a sibling-repository change. The existing service-common
  `BusinessException` mechanism is expected to preserve an error code and cause while allowing a
  filename-bearing message; stop and report the prerequisite if that is not true.
- Review `TransactionController.java`, preview API records, `TransactionImportService.java`,
  `TransactionDuplicateMatcher.java`, `PreviewImportTokenService.java`, statement extractor
  selection, `TransactionRepository.java`, and their focused tests.
- Review the preview sections of `docs/statement-import.md`, `docs/api/README.md`, and
  `docs/configuration.md`.

### Execution steps

1. Define the grouped preview API and service records. Keep per-file filename, statement format ID,
   preview token, exact-reupload status, and transactions nested under the corresponding source,
   and preserve collection order in every mapping.
2. Change `TransactionController.previewTransactions(...)` to bind a non-empty ordered
   `List<MultipartFile>` from repeated `files` parts and forward the complete collection through one
   service call. Keep parsing, hashing, and file coordination out of the controller.
3. Refactor `TransactionImportService` around one `@Transactional(readOnly = true)` grouped
   operation. Resolve the shared visible format once and process each file in multipart order, but
   do not return any preview data until every source succeeds.
4. At each parsing boundary, preserve the expected `BusinessException` error code and cause while
   adding the failing original filename to the safe message. For a missing or blank multipart
   filename, retain the current error code and identify the part by ordered index. Never expose
   contents, hashes, or stack details.
5. Make grouped preview duplicate matching load persisted candidates once, prefer
   `EXISTING_TRANSACTION`, and add a file's rows to the earlier-file candidate set only after that
   entire file has been evaluated. Add focused service, matcher, controller, authorization, and
   preview OpenAPI tests; then update the affected preview examples and combined-request-size
   documentation.

### Implementation notes

- This is an intentional breaking preview contract. Do not retain a parallel single-`file`
  controller path.
- Multipart order determines preview result order and later duplicate precedence.
- Parser revision IDs may differ between files because enabled revisions of the same public
  statement format can be selected independently.
- `PreviewDuplicateReason.IN_BATCH` remains the public reason value but now means a match in an
  earlier source file, never an earlier row from the same file.
- `max-file-size` continues to apply to each part; `max-request-size` applies to the combined
  multipart body. Do not change either configured limit.

### Validation

Run the focused preview and duplicate suites:

```bash
./gradlew test --tests '*TransactionDescriptionMatcherTest' \
  --tests '*TransactionDuplicateMatcherTest' \
  --tests '*TransactionImportServiceTest' \
  --tests '*TransactionControllerTest' \
  --tests '*TransactionControllerAuthorizationTest' \
  --tests '*TransactionOpenApiIntegrationTest'
```

Ensure MockMvc and OpenAPI assertions prove that two repeated multipart `files` parts produce two
ordered file results and distinct tokens, and that a failure in file two returns one standard error
containing file two's name with no partial preview body.

### Completion criteria

- Preview accepts a non-empty ordered `files` collection and returns one ordered grouped response.
- Every successful file has its own hash status, parser revision-backed token, and transactions.
- The shared statement format is resolved once and the entire preview service call is read-only
  transactional and all-or-error.
- Persisted exact matches are `EXISTING_TRANSACTION`; same-file repeats are unmarked; exact matches
  in later files are `IN_BATCH`.
- Controller, authorization, OpenAPI, matcher, and preview service tests pass.
- Preview API, import, duplicate, and upload-limit documentation is accurate at this checkpoint.

## Phase 3: Implement Atomic Grouped Batch Import and Per-File Provenance

### Workspace

.

### Goal

Replace the single-token batch contract with one grouped request whose tokens are verified before
persistence and whose database work imports every accepted file group atomically with correct
per-file provenance and aggregate results.

### Scope

- Make `BatchImportRequest` contain a validated, non-empty ordered collection of per-file requests,
  each with one preview token and its reviewed transactions.
- Return aggregate created/duplicate counts and ordered per-file results containing the verified
  source filename, per-file counts, and created transactions.
- Verify all tokens for the authenticated owner before calling `TransactionService` and reject
  groups whose verified statement format IDs or account IDs differ.
- Accept different parser revision IDs under the same statement format.
- Perform business validation, persisted candidate lookup, duplicate decisions, file-import
  resolution, and transaction inserts in one service transaction.
- Apply exact first-file-wins matching to batch groups, never comparing rows within their own file.
- Create or reuse one `FileImport` per non-empty accepted source group and link only that group's
  created transactions to it.
- Update batch controller, authorization, OpenAPI, service tests and the directly affected API,
  import, duplicate, and `AGENTS.md` documentation.

### Non-goals

- Supporting mixed statement format IDs or account IDs in one grouped import.
- Requiring equal parser revision IDs.
- Creating provenance for a file that creates no transactions.
- Changing content hashing, token encryption or TTL, hash ownership, soft deletion, or schema.
- Adding a combined `file_import` row or a database migration.
- Preserving the old single-token batch request as a fallback.

### Required context

- Confirm Phases 1 and 2 are complete and their focused tests pass.
- Read `AGENTS.md`, then read `../service-common/docs/code-quality-standards.md`,
  `../service-common/docs/error-handling.md`, and
  `../service-common/docs/testing-patterns.md` completely before changing Java.
- Review the grouped preview records and ordering semantics delivered in Phase 2.
- Review `TransactionController.java`, current batch request/response records,
  `PreviewImportTokenService.java`, `TransactionService.java`, service records under `service/dto`,
  `TransactionDuplicateMatcher.java`, `TransactionRepository.java`, `FileImport.java`, and
  `Transaction.java`.
- Review batch, token, provenance, and duplicate sections in `AGENTS.md`,
  `docs/statement-import.md`, `docs/duplicate-detection.md`, and `docs/api/README.md`.

### Execution steps

1. Define grouped batch API and service-layer records without `*Dto`-suffixed names. Nest reviewed
   transactions beneath their source file so Bean Validation paths contain both file and row
   indexes, and define aggregate plus ordered per-file response results.
2. At the controller boundary, verify every nested preview token for the current owner before any
   persistence call, convert the verified token and reviewed rows into service-layer groups, and
   enforce common verified statement format and account identities without enforcing a common
   parser revision. Invoke `TransactionService` exactly once.
3. Refactor `TransactionService.batchImport(...)` into one `@Transactional` grouped operation.
   Validate every row before writing and report nested file/transaction field paths plus the
   verified filename through the standard validation contract.
4. Load persisted duplicate candidates once, then evaluate file groups in request order against
   persisted rows and completed earlier groups. Respect each warned row's `allowDuplicate`, compute
   per-file and aggregate skipped/imported counts, and reject with
   `BATCH_IMPORT_NO_TRANSACTIONS_CREATED` only when the aggregate accepted set is empty.
5. Resolve or create provenance separately for each group that has accepted rows, use that group's
   created count as new provenance metadata, link its entities to that source, and save all groups
   within the same transaction. Add focused controller, authorization, batch OpenAPI, and service
   tests, then update the affected API/import/duplicate guidance and `AGENTS.md` quick references.

### Implementation notes

- Batch request order, not preview multipart order remembered elsewhere, is authoritative for batch
  first-file-wins behavior. Response order must follow the batch request.
- Verify all tokens before invoking the persistence service. Database reads and writes that decide
  the import result still belong inside the single service transaction.
- Continue to prefer a persisted `EXISTING_TRANSACTION` match when both persisted and earlier-file
  candidates match.
- A file may return a successful result with zero created rows when another group creates rows. It
  must not create a new `file_import` in that case.
- An existing `(content_hash, imported_by)` row remains advisory and is reused when transactions
  are intentionally accepted from that source.

### Validation

Run the focused grouped-batch suites:

```bash
./gradlew test --tests '*TransactionServiceTest' \
  --tests '*TransactionControllerTest' \
  --tests '*TransactionControllerAuthorizationTest' \
  --tests '*TransactionOpenApiIntegrationTest'
```

The focused assertions must cover nested request validation, all-token verification before the
service call, statement-format/account mismatch rejection, different parser revision acceptance,
stable response order, per-file and aggregate counts, same-file repeated rows, later-file skips,
and `allowDuplicate` imports.

### Completion criteria

- The old single-token batch contract is replaced by non-empty ordered file groups.
- All verified tokens share one statement format and account, while parser revisions may differ.
- Business validation and every import-determining database operation execute under one service
  transaction.
- Same-file repeats remain eligible, later-file exact matches are skipped by default, and
  `allowDuplicate` imports a warned later-file row.
- Aggregate empty-import behavior and zero-created per-file behavior match the stated rules.
- Accepted transactions are associated with their own source group's `FileImport`.
- Focused controller, authorization, OpenAPI, and service tests pass, and affected documentation
  describes the grouped batch contract.

## Phase 4: Prove End-to-End Atomicity, Provenance, and Failure Semantics

### Workspace

.

### Goal

Harden the completed grouped workflow with PostgreSQL and HTTP-level coverage for source linkage,
rollback, ordering, parser failures, and boundary conditions that unit tests cannot prove.

### Scope

- Add or update integration tests for multi-file preview and import.
- Prove separate source hashes produce separate provenance rows and correct transaction links.
- Prove existing hashes are reused and zero-created groups do not create provenance.
- Prove all-zero imports, identity mismatches, different parser revisions, and first-file-wins
  duplicate behavior through public or service boundaries as appropriate.
- Prove a later-group persistence failure rolls back transactions and newly created provenance from
  earlier groups.
- Audit controller, authorization, OpenAPI, and documentation coverage for contradictions or gaps
  exposed by the integration tests.

### Non-goals

- Adding new product behavior beyond the contracts completed in Phases 1 through 3.
- Weakening assertions to accommodate nondeterministic result order.
- Using mocks to claim database rollback or foreign-key provenance correctness.
- Modifying a sibling repository or adding a schema migration.

### Required context

- Confirm Phases 1 through 3 are complete and their focused tests pass.
- Read `AGENTS.md`, then read `../service-common/docs/code-quality-standards.md` and
  `../service-common/docs/testing-patterns.md` completely before changing Java tests.
- Review `TransactionImportServiceIntegrationTest.java`,
  `TransactionServiceIntegrationTest.java`, PostgreSQL test setup, file-import fixtures, and the
  final grouped API/service records.
- Review affected MockMvc, authorization, and OpenAPI tests before deciding whether a missing case
  belongs at the HTTP or service integration boundary.
- Re-read `docs/statement-import.md`, `docs/duplicate-detection.md`, `docs/api/README.md`,
  `docs/configuration.md`, and the service-specific quick references in `AGENTS.md`.

### Execution steps

1. Add integration coverage showing two distinct hashes create two `file_import` rows and every
   created transaction references the correct source; also prove an existing owner-scoped hash is
   reused.
2. Cover zero-created groups and aggregate-empty behavior: a skipped-only group creates no new
   provenance when another group succeeds, while an all-zero request returns the existing
   `BATCH_IMPORT_NO_TRANSACTIONS_CREATED` error.
3. Cover grouped identity and ordering rules, including accepted different parser revisions under
   one format, rejected statement-format/account mismatches, unflagged same-file repeats, and
   deterministic later-file duplicate handling.
4. Force a realistic database failure during a later group and assert that the transaction leaves
   both `transaction` and `file_import` tables unchanged. Do not simulate rollback solely with a
   mocked repository.
5. Run the combined integration and HTTP contract suites, fix production or test-fixture defects
   without weakening assertions, and correct any documentation contradicted by the proven behavior.

### Implementation notes

- Use the existing PostgreSQL/Testcontainers patterns and transaction boundaries; do not add a
  second test-only persistence path.
- Rollback assertions must distinguish pre-existing fixtures from rows attempted by the grouped
  request.
- Assert source linkage by durable identifiers or relationships, not only response counts.
- Preserve request order in fixtures so first-file-wins assertions are deterministic.

### Validation

Run the focused integration and boundary suites:

```bash
./gradlew test --tests '*TransactionImportServiceIntegrationTest' \
  --tests '*TransactionServiceIntegrationTest' \
  --tests '*TransactionControllerTest' \
  --tests '*TransactionControllerAuthorizationTest' \
  --tests '*TransactionOpenApiIntegrationTest'
```

Inspect the rollback test's final database assertions to confirm both transaction and newly
attempted file-import rows are absent after the induced later-group failure.

### Completion criteria

- Integration tests prove per-file provenance creation, reuse, linkage, and zero-created behavior.
- Integration tests prove different parser revisions are allowed while shared format/account
  mismatches are rejected.
- HTTP and service coverage prove stable ordering and first-file-wins duplicate semantics.
- A real later-group persistence failure rolls back all writes from the grouped request.
- Focused integration, controller, authorization, and OpenAPI tests pass.
- No tested behavior contradicts `AGENTS.md` or the affected documentation.

## Phase 5: Complete Documentation Audit and Repository Validation

### Workspace

.

### Goal

Finish the change with a repository-wide documentation consistency audit, formatting, all focused
regression suites, and the required clean full build.

### Scope

- Audit all affected documentation and code comments for obsolete single-file, fuzzy-match, or
  same-file `IN_BATCH` claims.
- Confirm examples show repeated `files` parts, grouped preview and batch JSON, per-file tokens,
  aggregate/per-file counts, first-error filename behavior, atomicity, and upload-limit semantics.
- Run the complete focused regression set from the original implementation plan.
- Apply repository formatting and run the full clean build in the required order.
- Resolve failures and inconsistencies within this repository without weakening tests.

### Non-goals

- Expanding the feature, changing public behavior, or adding backward-compatible fallbacks.
- Raising multipart limits, changing cryptography or TTLs, or adding a migration.
- Modifying the frontend, service-common, orchestration, or another sibling repository.
- Performing git write operations.

### Required context

- Confirm Phases 1 through 4 are complete and their focused tests pass.
- Read `AGENTS.md` and inspect the current worktree changes before editing.
- If Java fixes are required, read `../service-common/docs/code-quality-standards.md` completely
  before making them.
- Review `docs/statement-import.md`, `docs/duplicate-detection.md`, `docs/api/README.md`,
  `docs/configuration.md`, and all multi-file quick references in `AGENTS.md`.
- Review the final controller OpenAPI annotations and generated schema assertions alongside the
  documentation examples.

### Execution steps

1. Search production code, tests, `AGENTS.md`, and affected docs for obsolete `file` multipart
   examples, single-token batch shapes, fuzzy duplicate language, and claims that `IN_BATCH` can
   mean another row in the same file. Correct every contradiction.
2. Run the full focused regression command covering matcher, preview, batch, controller,
   authorization, OpenAPI, and both integration suites. Resolve failures without narrowing the
   intended assertions.
3. Run `./gradlew clean spotlessApply`, review formatter changes for semantic accidents, and rerun
   any focused test affected by a substantive correction.
4. Run `./gradlew clean build`. If service-common cannot resolve, stop and report that dependency
   preparation must be run from a separate phase whose workspace is `../service-common`; do not
   switch repositories from this phase.
5. Inspect the final diff and generated OpenAPI assertions to confirm the complete contract,
   documentation, and tests agree and that no migration, combined source identity, or compatibility
   fallback was introduced.

### Implementation notes

- `spotlessApply` is intentionally run before the final clean build, matching repository
  instructions.
- AI Session Handler's workspace boundary forbids running the documented sibling
  `publishToMavenLocal` recovery command from this phase. Dependency failure is a prerequisite
  blocker that must be handled by a separately declared `../service-common` phase or plan.
- Do not report completion based only on focused tests. The clean full build is required.

### Validation

Run the complete focused regression suite:

```bash
./gradlew test --tests '*TransactionDescriptionMatcherTest' \
  --tests '*TransactionDuplicateMatcherTest' \
  --tests '*TransactionImportServiceTest' \
  --tests '*TransactionServiceTest' \
  --tests '*TransactionControllerTest' \
  --tests '*TransactionControllerAuthorizationTest' \
  --tests '*TransactionOpenApiIntegrationTest' \
  --tests '*TransactionImportServiceIntegrationTest' \
  --tests '*TransactionServiceIntegrationTest'
```

Then run the repository-required commands in order:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Manually confirm the final tests and OpenAPI assertions demonstrate:

- two ordered multipart files return two ordered preview results and distinct tokens;
- the first parsing failure names its source and returns no partial preview payload;
- same-file repeats remain unmarked while a matching later-file row is `IN_BATCH`;
- persisted exact normalized matches are `EXISTING_TRANSACTION` and fuzzy-only descriptions are
  not duplicates;
- grouped batch results contain correct aggregate and per-file counts and provenance links; and
- a later-group persistence failure leaves all request-related transaction and file-import rows
  absent.

### Completion criteria

- `AGENTS.md`, API/import/duplicate/configuration docs, OpenAPI annotations, and tests describe one
  consistent grouped workflow with no stale single-file or fuzzy-matching claims.
- All focused regression tests pass.
- Formatting is applied and `./gradlew clean build` succeeds.
- No database migration, combined hash, synthetic file-import row, sibling source change, or legacy
  endpoint fallback is present.
- The implementation satisfies the original multi-file preview/import outcome through five
  durable, independently validated phase checkpoints.
