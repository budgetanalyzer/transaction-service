# Manual Transaction Creation Implementation Plan

Add an authenticated single-transaction creation path for manually recorded purchases while preserving the existing file preview and batch import workflow. The implementation is confined to transaction-service, uses the authenticated owner, and creates an ordinary active transaction without file provenance, duplicate suppression, or saved-view membership.

The minimal persistence change is to make `transaction.bank_name` nullable. `transaction.account_id` and `transaction.file_import_id` are already nullable and must not receive new migrations or relationship changes. A missing bank is stored as null and means only “no bank recorded”; because this service omits null JSON properties, `bankName` is optional in `TransactionResponse` and absent from serialized responses when no bank was supplied. The separate batch import request keeps `bankName` required.

Manual amounts use the existing positive stored amount plus `DEBIT`/`CREDIT` direction convention. Manual dates use the existing transaction date window, and manually supplied currency codes are normalized to uppercase and validated as ISO 4217 codes. Manual creation saves every valid request independently, including repeated identical purchases. No unresolved design decision blocks implementation.

## Phase 1: Add nullable-bank persistence and the manual creation service path

### Workspace

.

### Goal

Make the domain capable of persisting transactions without a bank or file source, and add a transactional service operation that creates one owner-scoped manual transaction without invoking import or duplicate-detection behavior.

### Scope

Add the next ordered Flyway migration, relax only the entity constraint that requires `bankName`, add a service-layer creation command, implement direct JPA creation in `TransactionService`, reuse the existing date business rules, validate and normalize manually supplied currency codes, and add focused persistence and service integration tests.

### Non-goals

Do not change `accountId` or `fileImport` nullability, add account entities or foreign keys, alter update behavior, add saved-view membership, add transaction classifications, infer cash semantics, or modify preview tokens, file provenance, duplicate matching, batch atomicity, or soft deletion.

### Required context

Read `AGENTS.md`, `docs/domain-model.md`, `docs/database-schema.md`, `docs/statement-import.md`, `docs/duplicate-detection.md`, the complete ordered `src/main/resources/db/migration/` history, `../service-common/AGENTS.md`, `../service-common/docs/spring-boot-conventions.md`, `../service-common/docs/code-quality-standards.md`, `../service-common/docs/error-handling.md`, and `../service-common/docs/testing-patterns.md`. Inspect `Transaction`, `TransactionType`, `TransactionService`, `TransactionRepository`, `TransactionDuplicateIdentity`, `TransactionDuplicateMatcher`, `BatchImportTransactionRequest`, and the existing transaction repository and service integration tests before editing.

### Execution steps

1. Confirm JDK 25 and Docker/Testcontainers are available. If `service-common` does not resolve, follow the documented Maven Local recovery workflow without editing the sibling repository.
2. Add `V25__make_transaction_bank_name_nullable.sql` with the single schema constraint change needed for this workflow: drop `NOT NULL` from `transaction.bank_name`. Update the column comment if needed to state that null means no bank was recorded, and leave the existing bank index and duplicate-candidate index in place because PostgreSQL supports nullable indexed columns.
3. Remove `@NotNull` from `Transaction.bankName` and update its Javadoc to describe the nullable meaning. Do not add changes for `accountId` or `fileImport`; both already permit service-created rows without an account or uploaded source.
4. Add a service-layer `CreateTransactionCommand` record under `service/dto` containing `date`, `description`, `amount`, `currencyIsoCode`, `type`, optional `bankName`, and optional `accountId`. Keep the service independent of the API request package.
5. Add a `@Transactional` `TransactionService.createTransaction` operation that accepts the command and authenticated owner ID, applies the existing year-2000-through-tomorrow date rules, normalizes and validates the currency with the JDK ISO 4217 currency registry, maps the supplied fields, sets `ownerId`, leaves `fileImport` null, and saves through `TransactionRepository`.
6. Reuse small private date-rule helpers from both manual creation and batch validation so the rule cannot drift, while preserving the batch endpoint’s existing indexed `BatchValidationException` field paths and error contract. Use the existing date error codes for manual violations and add one narrowly named transaction currency error code if no current code accurately represents an invalid manual currency.
7. Keep manual creation entirely outside `TransactionImportService`, `PreviewImportTokenService`, `FileImportTrackingService`, `TransactionDuplicateMatcher`, and saved-view services. Do not query for an existing transaction before saving.
8. Extend `TransactionRepositoryIntegrationTest` to prove a row with null `bankName`, null `accountId`, and null `fileImport` persists and reloads successfully through the real PostgreSQL schema.
9. Extend `TransactionServiceIntegrationTest` to prove the create operation assigns the supplied owner, persists all required fields, normalizes a lowercase valid currency code, preserves supplied optional values, leaves omitted optional values and provenance null, and creates two distinct rows for two identical commands. Cover the service-owned old-date, future-date, and invalid-currency failures by stable exception code rather than message text, and retain the existing batch date-validation coverage.

### Implementation notes

The database column remains named `bank_name` and the Java property remains `bankName`. Do not store `"CASH"`, an empty sentinel, or any derived payment classification. Manual creation is an insert-only operation: ownership comes from the method argument supplied by the authenticated controller, and no client-supplied owner field belongs in the command.

Request-shape checks for positivity, decimal precision, required text, and string lengths belong to the API request in Phase 2. The service owns the date range and actual ISO currency validity. Keep batch import’s request and business-error shapes unchanged when sharing the date predicates.

### Validation

Run the focused real-database tests after the migration and service work:

```bash
./gradlew test \
  --tests org.budgetanalyzer.transaction.repository.TransactionRepositoryIntegrationTest \
  --tests org.budgetanalyzer.transaction.service.TransactionServiceIntegrationTest
```

Inspect the migration order and test database schema to confirm that only `bank_name` changed nullability and that existing non-null bank rows require no backfill.

### Completion criteria

The migrated schema and JPA entity accept a transaction with no bank, account, or file import; the service creates owner-scoped manual transactions directly; identical manual commands create distinct rows; shared date rules and manual ISO currency validation are covered; and focused repository and service tests pass without changing import behavior.

## Phase 2: Expose the authenticated create endpoint and response contract

### Workspace

.

### Goal

Expose `POST /v1/transactions` as a documented, validated, owner-scoped API that returns the created transaction with HTTP 201 and an absolute canonical `Location` header.

### Scope

Add the manual creation request model, controller mapping, API-to-service conversion, `TransactionResponse` bank nullability, OpenAPI documentation, and full-context controller, authorization, and OpenAPI contract tests.

### Non-goals

Do not accept owner identity, preview tokens, file metadata, duplicate overrides, categories, payment methods, transfer data, account entities, saved-view IDs, or update-only fields in the request. Do not alter `POST /v1/transactions/batch` or its required-bank request contract.

### Required context

Read `AGENTS.md`, `docs/api/README.md`, `docs/domain-model.md`, `../permission-service/docs/authorization-model.md`, `../service-common/AGENTS.md`, `../service-common/docs/spring-boot-conventions.md` sections on DTOs, OpenAPI nullability, service boundaries, and 201 responses, `../service-common/docs/error-handling.md`, and `../service-common/docs/testing-patterns.md`. Inspect `TransactionController`, `TransactionResponse`, `BatchImportTransactionRequest`, `TransactionUpdateRequest`, `SavedViewController` creation patterns, `ControllerIntegrationTestSupport`, `TransactionControllerIntegrationTest`, `TransactionControllerAuthorizationIntegrationTest`, and `TransactionOpenApiIntegrationTest`.

### Execution steps

1. Add an OpenAPI-annotated `CreateTransactionRequest` record with these fields and request-boundary constraints:
   - required `date`;
   - required nonblank `description`, with the existing manual update contract’s 500-character maximum;
   - required positive `amount` with at most 36 integer digits and 2 fractional digits to fit `NUMERIC(38,2)` and preserve the positive-amount-plus-direction convention;
   - required three-character `currencyIsoCode`;
   - required existing `TransactionType` (`DEBIT` or `CREDIT`);
   - optional `bankName` bounded by the transaction column’s 255-character capacity; and
   - optional freehand `accountId` bounded by the existing update request’s 100-character API limit.
2. Treat omitted or blank optional `bankName` and `accountId` as null at the controller conversion boundary so absence has one persisted representation. Do not translate a missing bank into cash or any other value. Construct `CreateTransactionCommand` in the controller rather than adding another service-to-API dependency.
3. Add `POST /v1/transactions` with `@PreAuthorize("hasAuthority('transactions:write')")`. Obtain the owner from `SecurityContextUtil` through the controller’s existing current-user helper, call the manual service operation, build an absolute `/v1/transactions/{id}` URI with `ServletUriComponentsBuilder.fromCurrentRequest()`, and return `ResponseEntity.created(location).body(TransactionResponse.from(created))`.
4. Document 201, `Location`, 400 validation, and 422 business-rule responses on the operation. Do not add a cross-user creation branch or make `transactions:write:any` a substitute for the self-scoped permission.
5. Change only the `TransactionResponse.bankName` schema metadata from required to optional and describe omission as “no bank recorded.” Keep the Java field nullable. Do not enable explicit JSON null serialization; the repository-wide `spring.jackson.default-property-inclusion: non_null` convention means the property is omitted when the entity value is null.
6. Add full-context controller tests using `ClaimsHeaderTestBuilder` and real application beans. Verify a minimal authenticated request returns 201, an absolute `Location` ending in the created ID, required response fields, the authenticated `ownerId`, and omitted `bankName`/`accountId`; then reload the row to verify null optional fields and null provenance. Add a request with optional bank and account values to verify they round-trip.
7. Add meaningful request validation cases for each missing required field, blank required text, nonpositive or over-precision amount, malformed currency length, and unsupported enum input. Add 422 contract checks for invalid ISO currency and out-of-range dates using stable error type/code fields.
8. Extend authorization coverage for unauthenticated creation (401), an authenticated caller without `transactions:write` (403), and a caller with `transactions:write` (201). Assert that the persisted owner is always the authenticated subject and that the request has no owner field to override.
9. Extend OpenAPI tests to assert that the root transactions path has a POST operation, the 201 response documents `TransactionResponse` and `Location`, the create schema requires exactly the intended required fields, `TransactionResponse.bankName` is not required, and `BatchImportTransactionRequest.bankName` remains required.

### Implementation notes

Use one request object for manual creation rather than reusing `BatchImportTransactionRequest`; the latter carries import-specific fields and must continue requiring `bankName`. Invalid JSON enum values remain ordinary 400 request failures. Positive amount and explicit direction avoid signed-amount inference and preserve existing storage and reporting behavior.

Keep logs limited to safe operational counts or IDs already allowed by repository policy. Do not log the request body, description, owner claims, or other financial details from the new code path.

### Validation

Run the focused API and security tests:

```bash
./gradlew test \
  --tests org.budgetanalyzer.transaction.api.TransactionControllerIntegrationTest \
  --tests org.budgetanalyzer.transaction.api.TransactionControllerAuthorizationIntegrationTest \
  --tests org.budgetanalyzer.transaction.api.TransactionOpenApiIntegrationTest
```

Inspect the generated `/v3/api-docs` assertions to confirm manual `bankName` is optional while import `bankName` remains required, and confirm every controller endpoint still has a fine-grained `@PreAuthorize` annotation.

### Completion criteria

An authenticated caller with `transactions:write` can create one transaction without a file, preview token, bank, or account; the response is 201 with the persisted representation and canonical location; ownership cannot be supplied by the client; validation and business errors use the standard contracts; and the import request schema remains unchanged.

## Phase 3: Prove nullable-bank compatibility, document behavior, and run all gates

### Workspace

.

### Goal

Verify that nullable manual transactions coexist safely with list/search/sort and file-import duplicate matching, update the repository-owned documentation, and complete every required validation gate.

### Scope

Add targeted regression coverage for nullable bank values and import matching, update active transaction-service documentation, run focused regressions, format the source, and run the full build.

### Non-goals

Do not introduce a “cash” filter, define a new null sort-order contract, change duplicate identity fields, add manual deduplication, alter saved views, modify update/delete behavior, or edit any sibling repository.

### Required context

Read `AGENTS.md`, `README.md`, `docs/api/README.md`, `docs/domain-model.md`, `docs/database-schema.md`, `docs/statement-import.md`, `docs/duplicate-detection.md`, `../service-common/docs/testing-patterns.md`, and the repository validation rules. Inspect `TransactionSpecifications`, `TransactionRepository`’s structured duplicate-candidate query, `TransactionDuplicateMatcher`, and the existing controller, specification, repository, import-service, and transaction-service integration tests.

### Execution steps

1. Add a focused list/search regression proving an active transaction with null `bankName` remains visible to its owner and in authorized cross-user search, serializes with the optional bank property absent, and does not match a concrete `bankName` filter.
2. Exercise `sort=bankName` with both banked and bankless rows to prove the allowed sort continues to execute and returns every row. Do not assert or document a new null placement guarantee; retain PostgreSQL/Spring Data ordering behavior and use an ID tiebreaker where the test needs deterministic comparison.
3. Add a duplicate-detection regression proving a bankless manual row does not match a banked import candidate because SQL equality on the required import bank does not match null. Preserve existing coverage showing that owner scope, active-row filtering, strict bank identity, normalized description matching, `allowDuplicate`, preview tokens, per-file provenance, and atomic grouped batches still behave as documented.
4. Confirm tests also demonstrate that manual creation performs no duplicate lookup: two identical manual purchases are persisted independently. Do not add duplicate controls or `allowDuplicate` to the manual request.
5. Update `README.md` to include manual single-transaction creation in the service’s supported usage without changing setup prerequisites.
6. Update `docs/api/README.md` with the create endpoint, permission, request/response example, 201/`Location` behavior, validation rules, authenticated ownership, optional bank/account behavior, and explicit separation from preview-token imports.
7. Update `docs/domain-model.md` and `docs/database-schema.md` so `bankName`/`bank_name` is optional and null means no bank was recorded; identify `accountId` and `fileImport` as already optional; describe manually created rows as having no file provenance; and keep the nullable bank and duplicate-candidate indexes accurate.
8. Update `docs/duplicate-detection.md` to state that duplicate detection is limited to preview/batch imports, manual creation never silently deduplicates, bankless manual rows cannot equal required-bank import identities, and a manually created row with a supplied matching bank remains an ordinary active persisted candidate under the existing rule.
9. Update `docs/statement-import.md` to keep the import boundary explicit: batch imports still require preview tokens and nonblank bank names, keep file provenance and atomic grouped behavior, and do not provide a no-file route through the batch endpoint.
10. Run focused regression suites, then the mandatory formatter and full build. Inspect all output and fix Checkstyle warnings even if Gradle exits successfully. If dependency resolution fails, use the documented `service-common` Maven Local recovery workflow and retry; if Docker or another required verifier is unavailable, report the exact unverified gate rather than claiming completion.
11. Run the documentation checks required by `AGENTS.md`: `git diff --check -- AGENTS.md README.md docs`, verify every changed local link and anchor, and syntax-check each changed command example. Review the final diff to confirm every source-controlled edit is inside transaction-service and no out-of-scope behavior was added.

### Implementation notes

No specification or sorting implementation change is expected: unfiltered queries already admit null fields, a concrete bank filter naturally excludes null, and PostgreSQL can sort nullable columns. Change those components only if a focused regression exposes an actual failure.

The duplicate repository query continues to receive nonblank bank names from the import contract. Its equality join intentionally does not match a persisted null bank. Do not broaden the query with `IS NOT DISTINCT FROM`, coalesce null to a sentinel, or change the duplicate index.

### Validation

Run the nullable-bank and import regressions before the full gates:

```bash
./gradlew test \
  --tests org.budgetanalyzer.transaction.api.TransactionControllerIntegrationTest \
  --tests org.budgetanalyzer.transaction.repository.TransactionRepositoryIntegrationTest \
  --tests org.budgetanalyzer.transaction.repository.spec.TransactionSpecificationsIntegrationTest \
  --tests org.budgetanalyzer.transaction.service.TransactionImportServiceIntegrationTest \
  --tests org.budgetanalyzer.transaction.service.TransactionServiceIntegrationTest
```

Then run the required repository-wide sequence and documentation checks:

```bash
./gradlew clean spotlessApply
./gradlew clean build
git diff --check -- AGENTS.md README.md docs
```

### Completion criteria

Nullable-bank transactions are covered across persistence, response serialization, owner-scoped listing, authorized search, bank filtering, sorting, and import duplicate matching; manual repeats remain distinct; import contracts and provenance behavior remain intact; active owner documentation is accurate; focused tests pass; the formatter and full build pass with no Checkstyle warnings; and documentation links and commands have been checked.
