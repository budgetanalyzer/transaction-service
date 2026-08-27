# Saved-View Duplicate-Name Business Error Plan

Translate the case-insensitive per-owner saved-view name constraint introduced by
`V24__enforce_unique_saved_view_names.sql` into the transaction service's normal business-error
contract. Duplicate create and rename requests must return HTTP 422 with error type
`APPLICATION_ERROR`, a safe human-readable message, and code
`SAVED_VIEW_NAME_ALREADY_EXISTS`. The database constraint remains the concurrency-safe source of
truth; the service converts only the expected unique-violation path and preserves unexpected
persistence failures as HTTP 500 errors.

Execute the phases in order. Each phase stays within this repository, leaves a focused verified
checkpoint, and preserves the existing owner scoping, lifecycle locking, membership validation,
and case-insensitive database semantics. Statement-format display-name uniqueness is a separate
product and namespace decision and is intentionally excluded.

## Phase 1: Translate the Saved-View Name Constraint in the Service

### Workspace

.

### Goal

Make saved-view create and rename operations convert the database's expected unique-name failure
into a `BusinessException` carrying `SAVED_VIEW_NAME_ALREADY_EXISTS`, including when concurrent
requests bypass any opportunity for an application-level pre-check.

### Scope

- Add `SAVED_VIEW_NAME_ALREADY_EXISTS` to `BudgetAnalyzerError` with an accurate OpenAPI
  description.
- Keep `uq_saved_view_user_name_ci` as the authoritative case-insensitive, per-owner invariant.
- Force persistence inside the service transaction for both create and rename so the constraint
  failure is translated before the service returns.
- Recognize the PostgreSQL unique-violation SQL state only within the narrowly scoped saved-view
  persistence path.
- Throw the standard shared `BusinessException` with a safe message and the new error code.
- Add real-database service integration coverage for create, rename, owner scoping, case variants,
  rollback, and casing-only self-renames.

### Non-goals

- Adding a repository `exists` query or using check-then-write as the correctness mechanism.
- Adding a service-specific controller advice or changing `service-common` exception handling.
- Mapping every `DataIntegrityViolationException` to HTTP 422.
- Parsing database exception messages or depending on Hibernate-specific exception classes.
- Changing `V24__enforce_unique_saved_view_names.sql`, its whitespace semantics, or its
  case-insensitive comparison.
- Changing membership validation order, owner lookup behavior, lifecycle locks, or transaction
  membership persistence.
- Enforcing uniqueness for `statement_format.display_name`, bank names, filenames, or other
  labels.

### Required context

- Read `AGENTS.md`, `docs/saved-views.md`, `docs/domain-model.md`, `docs/database-schema.md`, and the
  complete ordered migration directory before editing.
- Read `../service-common/AGENTS.md`,
  `../service-common/docs/spring-boot-conventions.md`,
  `../service-common/docs/code-quality-standards.md`,
  `../service-common/docs/error-handling.md`, and
  `../service-common/docs/testing-patterns.md` completely.
- Review `SavedViewService`, `BudgetAnalyzerError`, `SavedViewRepository`,
  `SavedViewServiceIntegrationTest`, and `SavedViewSchemaMigrationTest`.
- Confirm a PostgreSQL-compatible container runtime is available for the Testcontainers tests.
- If the `service-common` artifact cannot resolve, use the documented Maven Local recovery build
  without editing the sibling repository.

### Execution steps

1. Add `SAVED_VIEW_NAME_ALREADY_EXISTS` to `BudgetAnalyzerError`. Describe it as a same-owner,
   case-insensitive saved-view name conflict; do not imply that whitespace is normalized or that
   different owners share one namespace.
2. In `SavedViewService`, centralize the create/rename persistence operation in a small private
   helper, or use two equally narrow guarded persistence blocks if that is clearer. Use
   `saveAndFlush` or an explicit repository `flush` so both methods encounter constraint failures
   inside the service transaction.
3. Catch `DataIntegrityViolationException` only around saved-view persistence. Traverse its cause
   chain using standard Java `SQLException` APIs and recognize PostgreSQL SQL state `23505` as the
   unique-violation signal. Translate that signal to `new BusinessException(...)` with the safe
   message `A saved view with that name already exists.` and code
   `BudgetAnalyzerError.SAVED_VIEW_NAME_ALREADY_EXISTS.name()`.
4. Rethrow the original persistence exception when the cause chain does not contain SQL state
   `23505`. Do not inspect localized database messages, import PostgreSQL driver classes, import
   Hibernate classes, or globally reinterpret unrelated integrity failures.
5. Do not attach the database exception as the client-facing business exception's cause and do not
   log the submitted view name or constraint detail. Preserve existing safe ID/count logging and
   let unexpected integrity failures follow the existing internal-error path.
6. Extend `SavedViewServiceIntegrationTest` with real PostgreSQL cases proving that an exact-name
   duplicate create and a case-variant duplicate create throw `BusinessException` with the new
   code, while another owner can use the same case-insensitive name.
7. Add rename cases proving that renaming one view to another same-owner view's exact or
   case-variant name returns the same business error and rolls the transaction back to the original
   persisted name. Also prove that renaming a view by changing only its own casing succeeds.
8. Add a synchronized concurrent-create integration case with empty memberships and the same owner
   and case-insensitive name. Assert that exactly one request succeeds, exactly one request receives
   `SAVED_VIEW_NAME_ALREADY_EXISTS`, and exactly one matching row exists afterward. Use the existing
   executor/latch conventions in `SavedViewServiceIntegrationTest`; do not mock repositories or
   application-owned Spring beans.

### Implementation notes

- Do not add a preflight `existsBy...` query. It adds a round trip and cannot protect create/create,
  create/rename, or rename/rename races. The existing unique index is the final arbiter for every
  path.
- SQL state recognition is intentionally inside the saved-view write boundary. At present the
  generated UUID primary key is not caller-controlled and `uq_saved_view_user_name_ci` is the only
  expected unique conflict for this entity. If another saved-view unique invariant is introduced,
  revisit this mapping rather than silently broadening the meaning of the error code.
- Creation must continue validating and locking requested memberships before inserting the view,
  so a request that is both stale and name-conflicting retains the existing membership-validation
  precedence. Rename must continue resolving and locking the owner-scoped view before changing its
  name.
- A failed flush marks the transaction for rollback. Re-throwing the translated runtime business
  exception from the transactional service boundary must leave no partial view, membership, or
  rename state.
- Keep the implementation in the service layer: the constraint represents a service-owned
  business invariant, while the controller should remain unaware of persistence exceptions.

### Validation

Run the focused real-database service and migration tests:

```bash
./gradlew test --tests '*SavedViewServiceIntegrationTest' --tests '*SavedViewSchemaMigrationTest'
```

Then run the repository-required validation sequence and inspect the complete output, including
Checkstyle warnings even if Gradle exits successfully:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

### Completion criteria

- Duplicate create and rename operations throw `BusinessException` with code
  `SAVED_VIEW_NAME_ALREADY_EXISTS` from the service boundary.
- Exact and case-variant conflicts have identical behavior, while different owners may reuse a
  name and one view may change only its stored casing.
- Concurrent same-owner creates produce one saved view and one normal duplicate-name business
  error, without leaking a persistence exception.
- Rejected renames preserve the original persisted name and rejected creates persist no partial
  state.
- Non-unique persistence failures are not converted into the duplicate-name business error.
- No check-then-write query, Hibernate dependency, PostgreSQL driver compile dependency, sibling
  repository edit, or statement-format behavior change is introduced.
- Focused tests and the clean format/build sequence pass.

## Phase 2: Expose and Verify the HTTP 422 Contract

### Workspace

.

### Goal

Prove that the shared business-exception flow exposes duplicate saved-view names as the standard
HTTP 422 response for both public mutation endpoints and accurately advertises that behavior in
OpenAPI.

### Scope

- Add controller integration tests for duplicate-name create and rename requests.
- Assert the complete client-relevant response contract: status, type, safe message, and code.
- Ensure database exception and constraint details are absent from responses.
- Document HTTP 422 on both create and rename controller operations.
- Extend generated OpenAPI assertions for the two 422 responses and their shared error schema.

### Non-goals

- Catching exceptions in `SavedViewController` or constructing `ApiErrorResponse` manually.
- Adding a new response model, error envelope, HTTP status, endpoint, or permission.
- Changing request validation or using HTTP 409 instead of the established business-error 422.
- Exposing `BudgetAnalyzerError` as a replacement for the shared string `code` field.
- Repeating persistence implementation tests through controller internals.

### Required context

- Confirm Phase 1 is complete and its clean build passes.
- Read `AGENTS.md`, `docs/api/README.md`, and `docs/saved-views.md`.
- Read `../service-common/docs/error-handling.md` and
  `../service-common/docs/testing-patterns.md` completely before modifying tests.
- Review `SavedViewController`, `ControllerIntegrationTestSupport`,
  `SavedViewControllerAuthorizationIntegrationTest`, and
  `TransactionOpenApiIntegrationTest`.
- Confirm `ClaimsHeaderTestBuilder` remains the required authentication mechanism and no
  service-owned Spring bean is mocked or spied.

### Execution steps

1. Extend `SavedViewControllerAuthorizationIntegrationTest` with an authenticated
   `POST /v1/views` case that first persists a same-owner view, submits a case-variant duplicate,
   and asserts HTTP 422, `type=APPLICATION_ERROR`, the safe message, and
   `code=SAVED_VIEW_NAME_ALREADY_EXISTS`.
2. Add an authenticated `PATCH /v1/views/{id}` case that attempts to rename one owned view to a
   case-variant of another owned view's name and asserts the identical 422 error contract. Verify
   through the repository or a subsequent read that the rejected target retains its original name.
3. Assert that neither response contains `uq_saved_view_user_name_ci`, SQL state `23505`, raw SQL,
   a database exception class, nor other internal constraint diagnostics. Avoid brittle assertions
   against server logs or framework stack traces.
4. Update the `@ApiResponses` metadata on saved-view create and rename. Create already declares a
   generic 422 response, so clarify that it can represent stale membership or duplicate name;
   rename must gain a 422 `ApiErrorResponse` entry for the duplicate-name rule.
5. Extend the saved-view section of `TransactionOpenApiIntegrationTest` to assert that both
   operations advertise a 422 response with `ApiErrorResponse` content and that their descriptions
   identify `SAVED_VIEW_NAME_ALREADY_EXISTS`. Keep the assertion focused on the generated contract
   rather than SpringDoc implementation details.
6. Review existing 400, 404, permission, foreign-owner, and stale-membership assertions to ensure
   the new business error did not replace or weaken those paths. Add only a focused regression case
   if an existing boundary is not already covered.

### Implementation notes

- The shared `ServletApiExceptionHandler` already maps `BusinessException` to HTTP 422 and builds
  `ApiErrorResponse`. This phase verifies and documents that path; it must not duplicate the shared
  handler in this service.
- The response contract is:

  ```json
  {
    "type": "APPLICATION_ERROR",
    "message": "A saved view with that name already exists.",
    "code": "SAVED_VIEW_NAME_ALREADY_EXISTS"
  }
  ```

- Keep the message generic. The authenticated caller already supplied the conflicting candidate,
  but echoing it is unnecessary and could expose financial or organizational context through logs
  or error telemetry.
- Create's existing 422 membership error and the new name error share the envelope and differ by
  `code`; clients must branch on the code rather than message text.

### Validation

Run the focused controller and generated-contract tests:

```bash
./gradlew test --tests '*SavedViewControllerAuthorizationIntegrationTest' \
  --tests '*TransactionOpenApiIntegrationTest'
```

Then run the required repository sequence and inspect all output:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

### Completion criteria

- Duplicate create and rename requests return HTTP 422 through the shared business-error handler.
- Both responses contain exactly the expected client-relevant type, safe message, and new code,
  without database or constraint details.
- Create retains its existing stale-membership 422 behavior and rename retains 400/404 behavior.
- Both mutation operations advertise the 422 error response in generated OpenAPI.
- Controllers remain thin and contain no persistence-exception handling or manual error-envelope
  construction.
- Focused tests and the clean format/build sequence pass.

## Phase 3: Align Owner Documentation and Run Final Validation

### Workspace

.

### Goal

Make the maintained saved-view documentation name the new client contract, audit the final change
for scope and safety, and complete every required repository validation gate.

### Scope

- Update the saved-view behavior and API owner documents with the exact 422 error code.
- Keep database documentation aligned with the unchanged `V24` invariant.
- Review the final implementation for narrow exception translation, transaction rollback, safe
  logging, and unchanged authorization semantics.
- Run focused schema, service, controller, OpenAPI, formatting, and full-build validation.

### Non-goals

- Revisiting whether statement-format display names should be unique.
- Adding migrations, indexes, cleanup SQL, global integrity-exception translation, or frontend
  changes.
- Updating archived plans or modifying sibling repository documentation.
- Weakening, deleting, or replacing existing tests.

### Required context

- Confirm Phases 1 and 2 are complete and their clean builds pass.
- Read `AGENTS.md` and recheck its documentation and validation requirements.
- Review the final diffs in `SavedViewService`, `BudgetAnalyzerError`, `SavedViewController`, all
  changed tests, `docs/saved-views.md`, `docs/api/README.md`, `docs/domain-model.md`, and
  `docs/database-schema.md`.
- Confirm Docker or another supported container runtime remains available for PostgreSQL
  Testcontainers.

### Execution steps

1. Update `docs/saved-views.md` so create and rename explicitly document HTTP 422
   `APPLICATION_ERROR` with `SAVED_VIEW_NAME_ALREADY_EXISTS`, including case-insensitive same-owner
   scope and same-name reuse by different owners.
2. Update the create and rename entries in `docs/api/README.md` with the same status, type, and code.
   Preserve the existing `SAVED_VIEW_MEMBERSHIP_STALE` create documentation and distinguish the two
   errors by code.
3. Update the saved-view business rules in `docs/domain-model.md` only as needed to link the
   uniqueness invariant to its public business error. Verify `docs/database-schema.md` still
   accurately describes `uq_saved_view_user_name_ci`; do not create documentation churn when its
   schema description requires no change.
4. Inspect the complete diff for accidental statement-format changes, repository pre-checks,
   Hibernate or PostgreSQL driver imports, exception-message parsing, unsafe logging, manual
   controller error mapping, or changes outside this repository. Remove any such scope expansion.
5. Run the focused saved-view schema, service, controller, and OpenAPI tests together. Confirm the
   migration test still proves the database constraint independently from the new service and HTTP
   translation tests.
6. Run Markdown whitespace validation, verify every changed local link target and anchor, and
   syntax-check every changed command. Then run the required clean formatting/build sequence and
   inspect Checkstyle output even if Gradle succeeds.

### Implementation notes

- The database owner document explains the invariant; the saved-view and API owner documents
  explain how clients observe violations. Avoid duplicating exception implementation details in
  schema documentation.
- The final behavior is intentionally specific: expected unique-name conflicts become 422, while
  unrelated integrity violations continue to become the shared generic 500 response.
- The migration performs no duplicate cleanup. Existing environments must already satisfy the
  documented V24 prerequisite; this plan does not invent a runtime workaround for migration-time
  duplicate data.
- If an unrelated test is already failing, stop and report it as required rather than changing or
  disabling the test.

### Validation

Run focused behavior and migration verification:

```bash
./gradlew test --tests '*SavedViewSchemaMigrationTest' \
  --tests '*SavedViewServiceIntegrationTest' \
  --tests '*SavedViewControllerAuthorizationIntegrationTest' \
  --tests '*TransactionOpenApiIntegrationTest'
```

Validate documentation whitespace and links/anchors, then run the mandatory Java validation gates:

```bash
git diff --check -- AGENTS.md README.md docs
./gradlew clean spotlessApply
./gradlew clean build
```

Inspect the final `git diff` and `git status --short` after validation. Do not commit, push, switch
branches, rewrite history, or modify sibling repositories.

### Completion criteria

- The saved-view, API, domain, and database owner documents consistently describe the invariant
  and public error behavior without redundant implementation detail.
- The final diff is limited to the saved-view duplicate-name business error, its tests, and owner
  documentation; statement-format naming behavior is unchanged.
- Schema tests prove the unique index, service tests prove race-safe business translation and
  rollback, controller tests prove the 422 payload, and OpenAPI tests prove the advertised contract.
- No sensitive view name, SQL, constraint detail, claims data, or persistence exception is exposed
  to clients or newly logged.
- Documentation checks, focused tests, `spotlessApply`, and the full clean build pass with no
  ignored Checkstyle warnings.
- Any unavailable verifier or unrelated pre-existing failure is reported precisely instead of
  being represented as successful validation.
