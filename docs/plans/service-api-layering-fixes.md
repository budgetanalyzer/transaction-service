# Service → API Layering Fixes

## Context

Follow-up to [permission-service F3](../../../permission-service/docs/plans/user-read-api-fixes.md#f3--investigate-transaction-service-service--api-dependencies).
Permission-service introduced `service/dto/UserActor` to break a
`service → api.response` cycle and deferred investigation of the same pattern
in `transaction-service` because the precedent here predated it.

This plan resolves F3 against `transaction-service`.

Post-implementation note (2026-04-09): the shared HTTP-side
`PreviewTransactionApi` record introduced by this plan was later split into
`api/request/BatchImportTransactionRequest` and
`api/response/PreviewTransactionResponse`. The service-layer
`service/dto/PreviewTransaction` remains the internal type.

## Investigation

`grep -rn 'import org.budgetanalyzer.transaction.api' src/main/java/org/budgetanalyzer/transaction/{service,repository}`
(2026-04-09) surfaces two distinct severities of crossing.

### High severity — `service → api.response` (must fix)

`PreviewTransaction` / `PreviewResponse` / `PreviewWarning` are HTTP-shaped
records (`@Schema`, `@NotNull`, `@NotBlank`) used as the extraction pipeline's
working type. The service layer owns the extraction logic but reaches up into
`api.response` to name its own return values, inverting the dependency.

Affected files:

- `service/extractor/StatementExtractor.java` — the interface itself. Its
  inner `ExtractionResult(List<PreviewTransaction>, List<PreviewWarning>)`
  record binds every implementation to `api.response`.
- `service/extractor/CapitalOneBankMonthlyStatementExtractor.java:23`
- `service/extractor/CapitalOneCreditMonthlyStatementExtractor.java:23`
- `service/extractor/CapitalOneCreditYearlySummaryExtractor.java:20`
- `service/extractor/ConfigurableCsvStatementExtractor.java:23`
- `service/TransactionImportService.java:11` — returns `PreviewResponse`
  directly from `previewFile(...)`.
- `service/TransactionService.java:20` — `batchImport(List<PreviewTransaction>, ...)`
  consumes an `api.response` type as its input. (Note: `PreviewTransaction`
  is also reused as a request payload by `BatchImportRequest` — its current
  location in `api.response` is already awkward.)

### Low severity — `service → api.request` (accept + document)

`TransactionFilter` is bound from Spring MVC query parameters
(`@DateTimeFormat(iso = ISO.DATE)` on `dateFrom`/`dateTo`/`createdAfter`/...).
Its fields map 1:1 to the search spec in `TransactionSpecifications.withFilter(...)`.
Moving it to `service/dto/` would force the controller to translate between
two identical records on every request for no semantic gain. F3 explicitly
flagged filters as the acceptable side of the crossing, and ecosystem
precedent agrees.

Affected files:

- `service/TransactionService.java:19` (`search`, `countNotDeleted`, `countNotDeletedForUser`)
- `service/SavedViewService.java:16` (internal `criteriaToFilter` bridge)
- `repository/spec/TransactionSpecifications.java:11`

### Medium severity — request-body crossings (fix)

`{Create,Update}SavedViewRequest` and `{Create,Update}StatementFormatRequest`
are HTTP request bodies with `@NotBlank`/`@Size`/`@Pattern`/`@Valid`
annotations. Unlike `TransactionFilter`, the services already unpack these
into ≤4 discrete fields (`request.name()`, `request.criteria().toDomain()`,
`request.openEnded()`, etc.), so mapping at the controller boundary is
straightforward and removes the bind-annotation leakage.

Affected files:

- `service/SavedViewService.java:15,17` — `createView`, `updateView`
- `service/StatementFormatService.java:12-13` — `createFormat`, `updateFormat`

## Decisions

1. **Fix `api.response` crossings** by introducing
   `service/dto/{PreviewTransaction, PreviewWarning, PreviewResult}`.
   The api-layer records remain as the HTTP binding types and gain static
   `from(...)` factories to convert from the service DTOs. Controllers map at
   the boundary.
2. **Rename + relocate `api.response.PreviewTransaction` →
   `api.PreviewTransactionApi`.** The HTTP record is used in both directions
   (response from `/preview`, request body in `BatchImportRequest` for
   `/batch`), so living in `api.response/` mislabels it. `api.ViewCriteriaApi`
   is the existing precedent for bi-directional HTTP records. The `Api`
   suffix also disambiguates from the new `service.dto.PreviewTransaction`.
3. **Fix request-body crossings** by introducing service-layer command
   records (`SavedViewCommand`, `SavedViewPatch`, `StatementFormatCommand`,
   `StatementFormatPatch`) and converting in the controllers.
4. **Accept `TransactionFilter`** as an intentional crossing. Document the
   exception in `AGENTS.md` so future reviews do not re-flag it.

All changes are local, reversible, preserve production behavior, and are
verified per step with `./gradlew clean spotlessApply && ./gradlew clean build`.

## Step 1 — Introduce `service/dto/PreviewTransaction` and `PreviewWarning`

Create `src/main/java/org/budgetanalyzer/transaction/service/dto/PreviewTransaction.java`:

```java
package org.budgetanalyzer.transaction.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.budgetanalyzer.transaction.domain.TransactionType;

/** Service-layer preview of an extracted transaction (no HTTP annotations). */
public record PreviewTransaction(
    LocalDate date,
    String description,
    BigDecimal amount,
    TransactionType type,
    String category,
    String bankName,
    String currencyIsoCode,
    String accountId) {}
```

Create `src/main/java/org/budgetanalyzer/transaction/service/dto/PreviewWarning.java`:

```java
package org.budgetanalyzer.transaction.service.dto;

/** Service-layer warning for a previewed transaction field. */
public record PreviewWarning(int index, String field, String message) {}
```

No behavior yet — these are pure data records. Verify the build still compiles.

## Step 2 — Migrate `StatementExtractor` and all four implementations

`service/extractor/StatementExtractor.java`:

- Swap the two `api.response.*` imports for `service.dto.*` equivalents.
- `ExtractionResult` record fields now use `service.dto.PreviewTransaction`
  and `service.dto.PreviewWarning`.

`service/extractor/CapitalOneBankMonthlyStatementExtractor.java`,
`CapitalOneCreditMonthlyStatementExtractor.java`,
`CapitalOneCreditYearlySummaryExtractor.java`,
`ConfigurableCsvStatementExtractor.java`:

- Swap `import org.budgetanalyzer.transaction.api.response.PreviewTransaction;`
  for `import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;`
- Constructor calls `new PreviewTransaction(...)` compile against the new
  type unchanged (same field order).

Verification: zero `api.response` imports in `service/extractor/**`:

```bash
grep -rn 'api\.response' src/main/java/org/budgetanalyzer/transaction/service/extractor/
```

## Step 3 — Introduce `service/dto/PreviewResult` and migrate `TransactionImportService`

Create `src/main/java/org/budgetanalyzer/transaction/service/dto/PreviewResult.java`:

```java
package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Service-layer result of previewing a statement file before import. */
public record PreviewResult(
    String sourceFile,
    String detectedFormat,
    List<PreviewTransaction> transactions,
    List<PreviewWarning> warnings) {}
```

`service/TransactionImportService.java`:

- Drop `import org.budgetanalyzer.transaction.api.response.PreviewResponse;`
- Add `import org.budgetanalyzer.transaction.service.dto.PreviewResult;`
- Change `previewFile` and `previewWithExtractor` return types to `PreviewResult`.
- At line 92, replace `new PreviewResponse(...)` with `new PreviewResult(...)`.

## Step 4 — Migrate `TransactionService.batchImport`

`service/TransactionService.java`:

- Drop `import org.budgetanalyzer.transaction.api.response.PreviewTransaction;`
- Add `import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;`
- `batchImport`, `validateBusinessRules`, `buildDuplicateKey`, `mapToEntity`
  parameter types flip to `service.dto.PreviewTransaction`. No body changes
  — field accessors are identical.

After this step:

```bash
grep -rn 'api\.response' src/main/java/org/budgetanalyzer/transaction/service/
```

should return zero matches.

## Step 5 — Rename `api.response.PreviewTransaction` → `api.PreviewTransactionApi`

After Steps 1-4, the only remaining references to the HTTP-side preview
record are `api.response.PreviewResponse`, `api.request.BatchImportRequest`,
`api.TransactionController`, and test classes. That makes this a good moment
to fix the mislabeling: the record is bound from both requests (batch
import) and responses (preview), so it does not belong in `api.response/`.
Follow the existing `api.ViewCriteriaApi` precedent.

Mechanical rename:

1. Move `src/main/java/org/budgetanalyzer/transaction/api/response/PreviewTransaction.java`
   to `src/main/java/org/budgetanalyzer/transaction/api/PreviewTransactionApi.java`.
2. Rename the record `PreviewTransaction` → `PreviewTransactionApi`; update
   the package declaration to `org.budgetanalyzer.transaction.api`.
3. Update importers:
   - `api/response/PreviewResponse.java` — `List<PreviewTransaction>` →
     `List<PreviewTransactionApi>`; add `import org.budgetanalyzer.transaction.api.PreviewTransactionApi;`
   - `api/request/BatchImportRequest.java` — same swap; drop the
     `api.response.PreviewTransaction` import.
   - `api/TransactionController.java` — no direct import of
     `PreviewTransaction` today, but verify after Step 6 wiring.
4. Update test classes that import `api.response.PreviewTransaction` to
   import `api.PreviewTransactionApi` instead:
   - `test/.../api/TransactionControllerTest.java:48`
   - `test/.../api/TransactionControllerAuthorizationTest.java` (if it
     references the type directly)

`PreviewWarning` stays in `api.response/` — it is only referenced from
`PreviewResponse` and is legitimately response-only.

Verification:

```bash
grep -rn 'api\.response\.PreviewTransaction' src/
# Expected: zero matches
```

## Step 6 — Map at the controller boundary for preview + batch import

`api/response/PreviewResponse.java` — add a factory:

```java
public static PreviewResponse from(PreviewResult previewResult) {
  return new PreviewResponse(
      previewResult.sourceFile(),
      previewResult.detectedFormat(),
      previewResult.transactions().stream().map(PreviewTransactionApi::from).toList(),
      previewResult.warnings().stream().map(PreviewWarning::from).toList());
}
```

`api/PreviewTransactionApi.java` — add conversions in both directions:

```java
public static PreviewTransactionApi from(
    org.budgetanalyzer.transaction.service.dto.PreviewTransaction serviceDto) {
  return new PreviewTransactionApi(
      serviceDto.date(), serviceDto.description(), serviceDto.amount(),
      serviceDto.type(), serviceDto.category(), serviceDto.bankName(),
      serviceDto.currencyIsoCode(), serviceDto.accountId());
}

public org.budgetanalyzer.transaction.service.dto.PreviewTransaction toServiceDto() {
  return new org.budgetanalyzer.transaction.service.dto.PreviewTransaction(
      date, description, amount, type, category, bankName, currencyIsoCode, accountId);
}
```

`api/response/PreviewWarning.java` — add `from(service.dto.PreviewWarning)`.

`api/TransactionController.java`:

- `previewTransactions(...)` — wrap the service call:
  `return PreviewResponse.from(transactionImportService.previewFile(...));`
- `batchImportTransactions(...)` — map the request list before calling the
  service:
  ```java
  var serviceDtos = request.transactions().stream()
      .map(PreviewTransactionApi::toServiceDto)
      .toList();
  var result = transactionService.batchImport(serviceDtos, userId);
  ```

The `api.request.BatchImportRequest` record keeps its current
`List<PreviewTransactionApi>` field — it is the HTTP binding shape and stays
in the api layer.

## Step 7 — Introduce service-layer command DTOs for saved views

Create `src/main/java/org/budgetanalyzer/transaction/service/dto/SavedViewCommand.java`:

```java
package org.budgetanalyzer.transaction.service.dto;

import org.budgetanalyzer.transaction.domain.ViewCriteria;

/** Service-layer command to create a saved view. */
public record SavedViewCommand(String name, ViewCriteria criteria, boolean openEnded) {}
```

Create `src/main/java/org/budgetanalyzer/transaction/service/dto/SavedViewPatch.java`:

```java
package org.budgetanalyzer.transaction.service.dto;

import org.budgetanalyzer.transaction.domain.ViewCriteria;

/**
 * Service-layer patch for updating a saved view. Null fields are skipped;
 * Boolean (not boolean) allows distinguishing "unset" from "false".
 */
public record SavedViewPatch(String name, ViewCriteria criteria, Boolean openEnded) {}
```

`service/SavedViewService.java`:

- Drop `api.request.CreateSavedViewRequest` and `api.request.UpdateSavedViewRequest` imports.
- `createView(String userId, SavedViewCommand command)` — consume the command.
- `updateView(UUID viewId, String userId, SavedViewPatch patch)` — consume the patch.

`api/SavedViewController.java`:

- At the call sites, convert before calling the service:
  ```java
  var command = new SavedViewCommand(
      request.name(), request.criteria().toDomain(), request.openEnded());
  savedViewService.createView(userId, command);
  ```
  and equivalently for update.

The `toDomain()` call on `ViewCriteriaApi` was previously happening inside
the service; pushing it into the controller is consistent with "map HTTP
shapes at the boundary."

## Step 8 — Introduce service-layer command DTOs for statement formats

Create `src/main/java/org/budgetanalyzer/transaction/service/dto/StatementFormatCommand.java`
mirroring the shape of `CreateStatementFormatRequest` but without validation
annotations (validation stays at the HTTP boundary). Fields: `formatKey`,
`displayName`, `formatType`, `bankName`, `defaultCurrencyIsoCode`, the six
optional CSV header strings, `dateFormat`.

Create `src/main/java/org/budgetanalyzer/transaction/service/dto/StatementFormatPatch.java`
mirroring `UpdateStatementFormatRequest` — every field nullable, plus
`Boolean enabled`.

`service/StatementFormatService.java`:

- Drop the two `api.request.*` imports.
- `createFormat(StatementFormatCommand command)` consumes the command;
  `mapToEntity` switches from `CreateStatementFormatRequest` to the command.
- `updateFormat(String formatKey, StatementFormatPatch patch)` consumes the
  patch; `applyUpdates` switches.

`api/StatementFormatController.java`:

- Add conversion at the call sites. Since the command and request have
  identical field order, a single-expression `new StatementFormatCommand(
  request.formatKey(), request.displayName(), ...)` is adequate — no helper
  needed.

After this step:

```bash
grep -rn 'org\.budgetanalyzer\.transaction\.api\.request' src/main/java/org/budgetanalyzer/transaction/service/
```

should return only `TransactionFilter` imports in `TransactionService`,
`SavedViewService`, and `repository/spec/TransactionSpecifications.java`.

## Step 9 — Document the `TransactionFilter` exception in `AGENTS.md`

Add a short subsection under `Service-Specific Patterns` in
`transaction-service/AGENTS.md`:

```markdown
### Layering — `TransactionFilter` crossing

`TransactionFilter` lives in `api/request/` and is imported directly by
`TransactionService`, `SavedViewService`, and `TransactionSpecifications`.
This is an **intentional** `service → api.request` crossing: the record
carries `@DateTimeFormat` bind annotations for Spring MVC query-parameter
binding, and its fields map 1:1 to the JPA spec built in
`TransactionSpecifications.withFilter(...)`. Moving it to `service/dto/`
would require translating between two identical records on every call. All
other service → api crossings have been eliminated (see
`docs/plans/service-api-layering-fixes.md`).

If a new `service → api` import appears outside this single exception,
treat it as a layering violation and introduce a service-layer DTO
instead.
```

## Step 10 — Update tests

Service-layer tests that directly construct `api.response.PreviewTransaction`
/ `api.response.PreviewWarning` for stubs switch to the `service.dto` types:

- `test/.../service/TransactionServiceTest.java:37` — flip import to
  `service.dto.PreviewTransaction`; stubs already construct via
  field-positional constructor, no body changes.
- `test/.../service/extractor/CapOneBankMonthlyExtractorTest.java`,
  `CapOneCreditMonthlyExtractorTest.java`,
  `CapOneCreditYearlySummaryExtractorTest.java` — flip imports.

Controller-layer tests keep HTTP types but follow the Step 5 rename:

- `test/.../api/TransactionControllerTest.java:48` — swap
  `api.response.PreviewTransaction` for `api.PreviewTransactionApi`. The
  three inline constructor calls at lines 350, 388, 422 (`new
  org.budgetanalyzer.transaction.api.response.PreviewResponse(...)`) use
  `PreviewResponse` which stays in `api.response/` — no rename there, but
  the `PreviewTransaction` elements passed in become `PreviewTransactionApi`.
- `test/.../api/TransactionControllerAuthorizationTest.java:41` — only
  imports `PreviewResponse`, which does not rename. No change.

For `SavedViewServiceTest.java` / `StatementFormatServiceTest.java`, replace
`CreateSavedViewRequest` / `UpdateSavedViewRequest` / `CreateStatementFormatRequest`
/ `UpdateStatementFormatRequest` stubs with the new command/patch records.
Controller tests (`SavedViewControllerTest`, `StatementFormatControllerTest`,
and their `*AuthorizationTest` siblings) still construct the request
records — they are the HTTP payloads under test.

## Verification

```bash
cd /workspace/transaction-service
./gradlew clean spotlessApply
./gradlew clean build
```

Expected post-conditions:

```bash
# Zero api.response imports anywhere in main sources outside api/:
grep -rn 'org\.budgetanalyzer\.transaction\.api\.response' \
    src/main/java/org/budgetanalyzer/transaction/{service,repository}
# Only TransactionFilter in api.request imports from service/repository:
grep -rn 'org\.budgetanalyzer\.transaction\.api\.request' \
    src/main/java/org/budgetanalyzer/transaction/{service,repository} \
    | grep -v TransactionFilter
# The renamed HTTP record no longer lives under api.response:
grep -rn 'api\.response\.PreviewTransaction\b' src/
```

All three should return empty. All existing tests must pass. No new
Checkstyle warnings (treat warnings as blockers per
`service-common/docs/code-quality-standards.md` §"Checkstyle Warning Handling").

## Out of scope — follow-ups

### FX1 — Sweep `ViewCriteriaApi.toDomain()`

Sweep completed on 2026-04-09.

Findings:

- `api/ViewCriteriaApi.java` defines `toDomain()` and, after Step 7, its only
  call sites are the create/update handlers in `api/SavedViewController.java`.
  This is the desired ownership: the api-layer record may define the
  conversion, but the controller performs it at the HTTP boundary before
  calling the service layer.
- `api/request/BatchImportTransactionRequest.java` defines `toServiceDto()`
  and its only call site is `api/TransactionController.java` for
  `/v1/transactions/batch`. This already follows the same controller-owned
  pattern.
- No other api/request/response record currently defines a downward conversion
  helper such as `toDomain()`, `toServiceDto()`, `toEntity()`, or equivalent.
- `TransactionFilter` remains the one accepted `service -> api.request`
  crossing because it is the bound query-parameter shape consumed directly by
  the search spec; it is not part of this sweep.
- Response-side `from(...)` factories stay out of scope. They convert
  domain/service types to HTTP responses, which is already the correct
  dependency direction.

Follow-up plan:

1. Treat `ViewCriteriaApi.toDomain()` and
   `BatchImportTransactionRequest.toServiceDto()` as the repository precedent
   for bidirectional api records: helper methods on the api record are
   acceptable when the controller owns every call site.
2. Keep service and repository code free of calls to api-side conversion
   helpers. If a new request body needs conversion, map it in the controller
   and pass a domain/service DTO downstream.
3. Re-run this sweep whenever a new api record gains a `to*` method:
   `rg -n "toDomain\\(|toServiceDto\\(|toEntity\\(" src/main/java/org/budgetanalyzer/transaction/api`
   plus `rg -n "import org\\.budgetanalyzer\\.transaction\\.api" src/main/java/org/budgetanalyzer/transaction/{service,repository}`.
4. No code changes are required from FX1 today because the only comparable
   conversion already matches the intended pattern.
