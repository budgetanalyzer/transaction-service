# Multi-File Statement Preview and Import Plan

Extend the existing statement preview and token-backed batch import workflow so one request can
process multiple files of the same statement format and account while preserving per-file source
provenance. Preview and import must be all-or-nothing, return one grouped response, identify the
first failing filename through the standard API error contract, and warn only about exact
normalized matches against persisted transactions or transactions in earlier files. Repeated rows
within one faithful source file are valid and must not be treated as duplicates.

## Phase 1: Implement Atomic Multi-File Preview and Import

### Workspace

.

### Goal

Replace the single-file preview and batch contracts with grouped multi-file contracts, process all
selected files in multipart order under one service transaction, retain one encrypted preview token
and one `file_import` provenance relationship per source file, and simplify duplicate matching to
exact normalized matching with first-file-wins behavior across the selected files.

### Scope

- Change `POST /v1/transactions/preview` to accept a non-empty ordered `files` multipart collection
  plus the existing shared `statementFormatId` and optional shared `accountId`.
- Return one `PreviewResponse` containing an ordered file-results array. Each file result must keep
  the existing filename, statement format ID, preview token, exact-file reupload status, and
  transaction array.
- Change `POST /v1/transactions/batch` to accept a non-empty ordered file-import array. Each element
  must contain one preview token and that file's reviewed transactions.
- Return aggregate created/duplicate counts plus ordered per-file results containing the verified
  source filename, per-file counts, and created transactions.
- Verify every preview token for the authenticated owner before persistence and reject a grouped
  request whose verified tokens do not share the same statement format ID and account ID. Parser
  revision IDs may differ because separate files can select different enabled revisions of the same
  statement format.
- Make preview all-or-nothing with a read-only transaction and make batch persistence atomic with
  one read-write service transaction covering duplicate checks, `file_import` resolution, and all
  transaction inserts.
- Preserve per-user checks against active persisted transactions and exact-file hash reupload
  status.
- Replace fuzzy description matching with normalized exact description equality after the existing
  strict bank/date/canonical amount/type/currency candidate match.
- Do not flag repeated matching transactions within one file. Flag a transaction in a later file
  when it exactly matches a transaction in any earlier file, using multipart/request order to make
  the first file authoritative. Keep the existing `IN_BATCH` API reason but redefine and document
  it as an earlier-file match rather than an earlier-row-in-the-same-file match.
- Preserve `allowDuplicate`: warned rows are skipped by default and imported when their individual
  override is true.
- Preserve the current empty-import rule at request level: if no transactions remain across all
  files, return `BATCH_IMPORT_NO_TRANSACTIONS_CREATED`. A file with zero created transactions may
  still have a successful per-file result when another file creates transactions, and it must not
  create a new `file_import` row unless at least one of its rows is created.
- Update unit, controller, OpenAPI, authorization, and PostgreSQL integration coverage for the new
  contracts, duplicate semantics, provenance, and rollback behavior.
- Update `AGENTS.md`, `docs/statement-import.md`, `docs/duplicate-detection.md`,
  `docs/api/README.md`, and `docs/configuration.md` in the same phase.

### Non-goals

- Supporting different statement format IDs or account IDs within one preview/import request.
- Auto-detecting a bank format independently for each file.
- Returning a collection of parsing errors; processing stops at the first file failure.
- Treating repeated rows within one source file as suspicious or deduplicating them.
- Retaining conservative fuzzy/Levenshtein transaction-description matching.
- Changing the SHA-256 algorithm, preview token encryption, token TTL, file hash uniqueness, soft
  deletion, or transaction ownership rules.
- Adding a database migration; the existing one-`file_import`-to-many-transactions model already
  represents multiple source files correctly.
- Changing CSV/PDF wizard endpoints, which remain single-sample parser configuration workflows.
- Modifying the frontend or any sibling repository.
- Adding speculative backward-compatible `file`/single-token fallbacks alongside the new grouped
  contract.

### Required context

- Read `AGENTS.md` and verify that no documented prerequisite requires a sibling-repository change.
  No `service-common` enhancement is expected: its existing `BusinessException` handling can
  preserve the parser error code while returning a filename-bearing message.
- Before changing Java, read `../service-common/docs/code-quality-standards.md` completely.
- Read `../service-common/docs/error-handling.md` and
  `../service-common/docs/testing-patterns.md` for standard errors, AssertJ, MockMvc, and
  Testcontainers conventions.
- Review `src/main/java/org/budgetanalyzer/transaction/api/TransactionController.java`, the current
  preview/batch request and response records under `api/request` and `api/response`, and their
  controller/OpenAPI/authorization tests.
- Review `src/main/java/org/budgetanalyzer/transaction/service/TransactionImportService.java`,
  `TransactionService.java`, `TransactionDuplicateMatcher.java`,
  `TransactionDescriptionMatcher.java`, `PreviewImportTokenService.java`, and the service records
  under `service/dto`.
- Review `TransactionRepository.java`, `TransactionDuplicateIdentity.java`, `FileImport.java`, and
  `Transaction.java` before changing duplicate lookup or source linkage.
- Review `docs/statement-import.md`, `docs/duplicate-detection.md`, `docs/api/README.md`, and
  `docs/configuration.md` so the changed API and multipart-size behavior remain documented.

### Execution steps

1. Define the grouped API and service-layer records without introducing `*Dto`-suffixed types.
   Make `PreviewResponse` a wrapper around ordered per-file preview responses. Make
   `BatchImportRequest` contain a validated, non-empty ordered list of per-file requests, and make
   `BatchImportResponse` expose aggregate totals plus ordered per-file results. Keep transaction
   payloads nested under their source file so Bean Validation paths identify both the file and row
   indexes.
2. Change `TransactionController.previewTransactions(...)` to bind a non-empty
   `List<MultipartFile>` from the `files` part while retaining one shared `statementFormatId` and
   `accountId`. Pass the full ordered collection into one service call and map its grouped result
   without parsing, hashing, or coordinating files in the controller.
3. Refactor `TransactionImportService` around a public, read-only transactional multi-file preview
   operation. Resolve the visible statement format once, then read, hash, check reupload history,
   select a parser revision, extract transactions, and issue a distinct preview token for each file
   in multipart order. Do not return any preview data until every file succeeds.
4. At the per-file parsing boundary, catch expected parser `BusinessException` failures and rethrow
   through the same standard exception mechanism with the original error code and cause but a
   message that includes the failing original filename and parser failure. Stop on that first
   failure. For a missing or blank multipart filename, retain the existing error code and identify
   the file part by its ordered index because no trustworthy filename exists. Do not expose file
   contents, hashes, or internal stack details.
5. Simplify `TransactionDescriptionMatcher` so `match(...)` only compares the existing normalized
   forms for equality. Remove Levenshtein thresholds, numeric-token special cases, and their tests.
   Retain amount scale canonicalization and the strict bank/date/type/currency candidate lookup, so
   a duplicate is the strict financial identity plus normalized exact description equality.
6. Make `TransactionDuplicateMatcher` source-aware. Query persisted candidates once for all rows in
   the grouped preview, then process file groups in request order. Compare every row with persisted
   candidates and with rows accumulated from completed earlier files, but add the current file's
   rows to the cross-file candidate set only after the whole file has been evaluated. This ensures
   repeated rows inside the current file remain unflagged while later files receive `IN_BATCH`.
   Continue to prefer `EXISTING_TRANSACTION` when both persisted and earlier-file matches exist.
7. Change the batch controller boundary to verify every nested preview token for the current user,
   map each verified token and reviewed transaction array into one service-layer file group, and
   invoke `TransactionService` exactly once. Reject missing/invalid tokens before persistence and
   enforce equal verified statement format IDs and account IDs without requiring equal parser
   revision IDs.
8. Refactor `TransactionService.batchImport(...)` to accept all file groups in one `@Transactional`
   call. Validate every row before writing, reporting nested file/transaction field paths and the
   verified filename in standard validation messages. Perform one persisted-candidate lookup, then
   apply the same source-aware first-file-wins exact matching used by preview. Do not compare rows
   against other rows from their own file.
9. Track accepted entities by source group, compute aggregate and per-file skipped/imported
   duplicate counts, and reject only when the aggregate accepted set is empty. For each non-empty
   group, create or reuse the `FileImport` identified by that group's verified hash, use that
   group's created count as new provenance metadata, and link only that group's transactions to it.
   Persist all accepted transactions within the same transaction so any later file-import or
   transaction persistence failure rolls back every group.
10. Update controller tests for repeated multipart `files` parts, stable file order, shared
    format/account forwarding, grouped JSON, per-file hash status, nested batch validation, token
    verification, permissions, and aggregate/per-file response totals. Update OpenAPI assertions to
    require the new grouped schemas and to describe `IN_BATCH` as an earlier-file warning.
11. Update duplicate matcher and service unit tests to prove: normalized exact descriptions match;
    formerly fuzzy-only descriptions do not; persisted matches warn every matching row; identical
    rows within one file are not warned or skipped; only occurrences in later files are warned and
    skipped by default; multipart/request order chooses the winner; and `allowDuplicate` imports a
    warned later-file row.
12. Add or update integration tests proving that multiple source hashes create separate
    `file_import` rows, every created transaction links to its correct source, an existing hash is
    reused, a zero-created file does not create provenance, all-zero grouped imports retain the
    existing error, different parser revisions under one statement format are accepted, source
    format/account mismatches are rejected, and a database failure while processing a later group
    rolls back earlier groups.
13. Update all affected documentation in the same work. Replace single-file examples with repeated
    `files` multipart parts and grouped preview/import JSON; document first-error filename behavior,
    one-token-per-file handling, atomicity, exact normalized matching, persisted versus
    earlier-file warning reasons, first-file-wins order, same-file repeated-row behavior,
    per-file/aggregate counts, and the fact that `max-request-size` limits the combined multipart
    body while `max-file-size` still applies to each file. Update the service-specific quick
    references in `AGENTS.md`; do not alter wizard documentation to imply multi-file samples.
14. Run formatting and the focused controller, service, matcher, OpenAPI, and integration tests.
    Resolve failures without weakening assertions. Then run the repository-required clean format
    and full clean build sequence.

### Implementation notes

- This is an intentional breaking contract: replace multipart `file` with `files` and replace the
  single preview token/transaction list batch body with file groups. Do not maintain two competing
  controller paths unless a new user decision explicitly requires backward compatibility.
- Preserve collection order end to end. Multipart order determines preview order, batch request
  order determines duplicate precedence, and response order must match the corresponding request.
- A single HTTP response does not mean a combined source identity. Keep one hash, token, parser
  revision, reupload status, and `FileImport` association for each file.
- Preview has no writes, but its read-only transaction provides one consistent persisted-duplicate
  view and all-or-error service boundary. Batch token verification may happen before the service
  call, but all database reads and writes that determine the import outcome must occur in the one
  transactional batch service call.
- Preserve `PreviewDuplicateReason.IN_BATCH` for client compatibility, but eliminate every claim
  that it can arise from another row in the same file.
- Exact normalized description matching should reuse the current Unicode normalization rather than
  inventing a second canonicalization rule. Delete fuzzy-only code instead of leaving disabled
  thresholds or feature flags.
- Rows in the current file must be compared with the persisted snapshot and earlier file groups,
  not with a mutable row-by-row set for that same file. Add the entire current group to the
  earlier-file set only after its duplicate decisions are complete.
- Do not create a synthetic combined `file_import` row or combined content hash. No schema migration
  is needed because `transaction.file_import_id` already points to the correct per-file source.
- Keep existing semantics for exact reuploads: a matching `(content_hash, imported_by)` record is
  advisory and reused when rows are intentionally created from that file.
- Do not raise the configured upload limits without a separate product decision. Document that the
  existing request-size limit applies to the sum of all selected files and can be overridden with
  `TRANSACTION_IMPORT_MAX_REQUEST_SIZE`.

### Validation

Run focused tests while implementing, including the final concrete test classes that own the
changed behavior:

```bash
./gradlew test --tests '*TransactionDescriptionMatcherTest' \
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

Manually verify the generated OpenAPI document and MockMvc JSON assertions demonstrate these
contract examples:

- two multipart `files` parts produce one response with two ordered file results and two distinct
  preview tokens;
- a parse failure in the second file returns one standard error containing the second filename and
  no partial preview payload;
- equal rows repeated within file one are unflagged, while an equal row in file two is `IN_BATCH`;
- persisted exact normalized matches are `EXISTING_TRANSACTION`;
- fuzzy-only description similarity is not a duplicate;
- one grouped batch call returns aggregate and per-file counts and correct source links; and
- a later-group persistence failure leaves both `transaction` and `file_import` tables unchanged.

### Completion criteria

- One preview request accepts multiple ordered files of one statement format/account and returns
  one ordered grouped response with per-file tokens, file status, and transactions.
- The first parsing failure aborts preview and produces the standard error shape with the failing
  filename and original parser error code.
- One grouped batch request imports all file groups in a single database transaction and returns
  aggregate plus per-file results.
- Every created transaction links to the correct per-file provenance row, and rollback tests prove
  no partial file or transaction persistence.
- Duplicate warnings and authoritative batch checks use persisted transactions plus earlier files
  only, never other rows in the same file, with exact normalized rather than fuzzy description
  matching.
- First-file-wins and `allowDuplicate` behavior are deterministic and covered by tests.
- No database migration, combined hash, or cross-repository change is introduced.
- `AGENTS.md` and all affected API/import/duplicate/configuration documentation describe the shipped
  behavior with no single-file or fuzzy-matching contradictions.
- Focused tests pass, code is formatted, and `./gradlew clean build` succeeds.
