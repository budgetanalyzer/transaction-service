# Bound Saved-View Membership Requests And Clean Test Configuration

Limit every saved-view membership request array to the already exercised 10,000-entry capacity,
publish and test that API contract, remove brittle error-message assertions, and remove the unused
H2 test path now that Spring integration tests use PostgreSQL Testcontainers. The accepted
destructive migrations and the existing `/v1/views` compatibility decisions are outside this
plan.

## Phase 1: Enforce And Document The Membership Request Limit

### Workspace

.

### Goal

Reject saved-view create or membership-delta arrays containing more than 10,000 submitted entries
with the standard validation response before any JPQL `IN` query is constructed, while preserving
the currently supported 10,000-entry path and publishing the limit in OpenAPI and repository-owned
documentation.

### Scope

- Add a single compile-time request constraint for a maximum of 10,000 transaction IDs.
- Apply the constraint to `CreateSavedViewRequest.transactionIds` and to both arrays in
  `UpdateSavedViewTransactionsRequest`.
- Expose `maxItems: 10000` for all three arrays in the generated OpenAPI schema.
- Add controller and OpenAPI coverage for the inclusive boundary and oversized requests.
- Update `docs/api/README.md` and `docs/saved-views.md` with the exact per-array contract.

### Non-goals

- Do not change `SavedViewService`, repository queries, JDBC batching, lock ordering, or the
  existing 10,000-membership persistence implementation.
- Do not add service-layer validation for a request-shape constraint already enforced by every
  runtime caller through `@Valid`.
- Do not chunk requests or increase the supported maximum.
- Do not change saved-view migrations or the versioned `/v1/views` API design.

### Required context

- Read `AGENTS.md`, especially request validation ownership, architectural simplicity, saved-view
  invariants, documentation maintenance, and validation requirements.
- Read `../service-common/docs/code-quality-standards.md`,
  `../service-common/docs/testing-patterns.md`, and
  `../service-common/docs/spring-boot-conventions.md`.
- Read `docs/saved-views.md`, `docs/api/README.md`, the three saved-view request arrays, and their
  current controller and OpenAPI integration tests.
- Treat `SavedViewServiceIntegrationTest.createSupportsTenThousandMembershipIds` as evidence that
  10,000 is the supported inclusive persistence boundary.

### Execution steps

1. Introduce one package-local saved-view request-constraint owner containing the compile-time
   `10_000` maximum so Bean Validation and OpenAPI annotations cannot drift between request models.
2. Add `@Size(max = ...)` to the create membership array and both membership-delta arrays, with
   field-appropriate validation messages. Add `maxItems` to each `@ArraySchema` using the same
   constant; retain the existing required, positive-ID, disjointness, and nonempty-delta rules.
3. Extend `SavedViewControllerAuthorizationIntegrationTest` to prove that exactly 10,000 submitted
   entries remain accepted and that 10,001 entries are rejected with HTTP 400,
   `VALIDATION_ERROR`, and the relevant stable field name. Cover create and each delta array; use
   repeated valid IDs where useful so boundary testing does not require another 10,000-row fixture.
4. Extend `TransactionOpenApiIntegrationTest` to assert `maxItems = 10000` for create,
   `addTransactionIds`, and `removeTransactionIds`, alongside the existing item type and minimum
   assertions.
5. Update the saved-view sections in `docs/api/README.md` and `docs/saved-views.md` to state that
   each submitted array is limited to 10,000 entries before sorting and duplicate
   canonicalization. Clarify that the add and remove arrays each have the limit independently.
6. Apply formatting and run the focused saved-view controller, OpenAPI, and service integration
   tests. Fix implementation failures rather than weakening boundary assertions.

### Implementation notes

The limit counts raw JSON array entries, including duplicates, because Bean Validation runs before
service canonicalization. This bounds deserialization and query preparation work and gives clients
a deterministic 400 response. The existing behavior that canonicalizes duplicate valid IDs remains
unchanged. Tests must assert stable error fields rather than validation message text.

### Validation

Run:

```bash
./gradlew spotlessApply
./gradlew test \
  --tests 'org.budgetanalyzer.transaction.api.SavedViewControllerAuthorizationIntegrationTest' \
  --tests 'org.budgetanalyzer.transaction.api.TransactionOpenApiIntegrationTest' \
  --tests 'org.budgetanalyzer.transaction.service.SavedViewServiceIntegrationTest'
git diff --check -- src/main src/test docs/api/README.md docs/saved-views.md
```

Inspect the focused test output for failures and warnings. Verify the generated OpenAPI assertions
cover all three arrays and that the existing 10,000-membership service test still passes.

### Completion criteria

- Every public saved-view membership input array rejects 10,001 entries before controller-to-service
  conversion or persistence access.
- Exactly 10,000 entries remain valid, subject to the existing element and business rules.
- OpenAPI and both active saved-view owner documents state the same per-array maximum.
- Focused controller, OpenAPI, and service integration tests pass with no formatting or diff-check
  errors.

## Phase 2: Remove Brittle Assertions And The Unused H2 Test Path

### Workspace

.

### Goal

Align duplicate-name API tests with the stable error contract and make PostgreSQL Testcontainers
the only configured integration-test database without losing the database-error-detail safety
setting.

### Scope

- Remove both exact `$.message` assertions from duplicate-name controller tests.
- Preserve assertions for status, error type, error code, field-error shape, persistence rollback,
  and absence of persistence diagnostics.
- Stop documenting the exact duplicate-name message as a client contract.
- Remove the H2 version-catalog alias and Gradle test dependency.
- Remove H2 connection properties from `src/test/resources/application.yml` while retaining the
  PostgreSQL `logServerErrorDetail: false` Hikari property and other shared test settings.
- Keep README test prerequisites accurate.

### Non-goals

- Do not remove or weaken duplicate-name safety, rollback, case-insensitivity, or concurrency
  coverage.
- Do not change the runtime PostgreSQL datasource configuration or exception translation.
- Do not replace Testcontainers, consolidate unrelated containers, rename test classes, or change
  production dependencies.
- Do not change saved-view migrations or API versioning.

### Required context

- Read `AGENTS.md`, `../service-common/docs/testing-patterns.md`, and the duplicate-name tests in
  `SavedViewControllerAuthorizationIntegrationTest`.
- Read `build.gradle.kts`, `gradle/libs.versions.toml`, `src/main/resources/application.yml`,
  `src/test/resources/application.yml`, and the README testing section.
- Discover every Spring-context test and confirm that it supplies PostgreSQL Testcontainer dynamic
  datasource properties directly or inherits them from `ControllerIntegrationTestSupport` before
  removing the H2 fallback.

### Execution steps

1. Remove the exact duplicate-name `$.message` expectations from both controller integration
   tests. Keep `APPLICATION_ERROR`, `SAVED_VIEW_NAME_ALREADY_EXISTS`, field-error shape, persisted
   state, and `assertNoPersistenceDiagnostics` checks intact.
2. Update `docs/saved-views.md` so clients are directed to the stable HTTP status, error type, and
   error code without promising exact human-readable wording.
3. Re-run discovery for all `@SpringBootTest`, `@DataJpaTest`, and `@JdbcTest` classes and verify
   that no context depends on the H2 URL from `src/test/resources/application.yml`.
4. Remove `testImplementation(libs.h2)` from `build.gradle.kts` and the `h2` alias from
   `gradle/libs.versions.toml`.
5. Remove the H2 URL, username, password, and driver from the test application configuration.
   Retain `spring.datasource.hikari.data-source-properties.logServerErrorDetail: false` so
   duplicate-constraint diagnostics remain value-safe in PostgreSQL tests.
6. Update the README testing section to state that Spring integration tests use PostgreSQL
   Testcontainers and have no H2 fallback, if the existing wording does not already make that
   repository-wide prerequisite explicit.
7. Apply formatting, run the application-context and duplicate-name controller tests, and inspect
   the test runtime dependency graph to confirm H2 is absent.

### Implementation notes

Human-readable error messages remain present in responses but are not a stable programmatic
contract. `assertNoPersistenceDiagnostics` may continue scanning the complete serialized response
because it asserts prohibited diagnostic content rather than exact wording. Removing H2 must not
remove the Hikari PostgreSQL error-detail property that supports this safety behavior.

### Validation

Run:

```bash
./gradlew spotlessApply
./gradlew test \
  --tests 'org.budgetanalyzer.transaction.TransactionServiceApplicationTests' \
  --tests 'org.budgetanalyzer.transaction.api.SavedViewControllerAuthorizationIntegrationTest'
./gradlew dependencyInsight --dependency h2 --configuration testRuntimeClasspath
rg -n 'jdbc:h2|org\.h2|libs\.h2|com\.h2database' \
  build.gradle.kts gradle/libs.versions.toml src/test README.md docs || true
git diff --check -- build.gradle.kts gradle/libs.versions.toml src/test README.md docs/saved-views.md
```

The dependency insight command must report no matching H2 dependency. Inspect test output to
confirm the application context starts against PostgreSQL and duplicate-name responses still omit
database values and persistence diagnostics.

### Completion criteria

- No test asserts exact duplicate-name response wording, while stable contract and diagnostic
  safety coverage remain intact.
- H2 is absent from the version catalog, Gradle test runtime, and test datasource configuration.
- All Spring integration contexts still obtain PostgreSQL datasource properties from
  Testcontainers.
- The PostgreSQL error-detail safety property remains active in test configuration.
- Focused tests and dependency/configuration checks pass.

## Phase 3: Integrate Documentation And Run Full Validation

### Workspace

.

### Goal

Verify the combined request-limit and test-cleanup work against all repository standards and leave
a production-ready, fully documented change set.

### Scope

- Review the complete diff for consistency, scope, and accidental regressions.
- Confirm request validation, OpenAPI, active documentation, test configuration, and dependencies
  agree.
- Run the repository's required formatting, build, test, Checkstyle, Javadoc, and coverage gates.

### Non-goals

- Do not add unrelated refactors or broaden the work to the accepted unsafe migrations, API
  compatibility, repository chunking, or other review findings.
- Do not weaken, disable, or delete tests to satisfy validation.
- Do not modify sibling repositories.

### Required context

- Read `AGENTS.md` and the changed files from Phases 1 and 2.
- Re-read the validation and documentation-maintenance sections of `AGENTS.md`.
- Use `../service-common/docs/code-quality-standards.md` and
  `../service-common/docs/testing-patterns.md` as the final review checklist.

### Execution steps

1. Inspect the cumulative diff and verify that the only behavior change is the documented
   10,000-entry request ceiling, with test-only cleanup and dependency/configuration removal beside
   it.
2. Verify all three request arrays use the same compile-time maximum and expose the same OpenAPI
   `maxItems`, and that no service or repository limit was added redundantly.
3. Verify active API and saved-view documentation describe raw-entry counting, per-array delta
   limits, and stable error-code handling without stale H2 or exact-message guidance.
4. Verify H2 is absent, every Spring context remains PostgreSQL-backed, and the test Hikari
   error-detail property remains configured.
5. Run the required `clean spotlessApply` followed by `clean build`, inspect the complete output,
   and fix every Checkstyle warning even if Gradle exits successfully.
6. Run final diff, link-target, and worktree checks. Report any unavailable verifier explicitly
   instead of claiming full verification.

### Implementation notes

The full build is the final authority; focused tests from earlier phases are checkpoints, not
substitutes. Preserve the user's accepted migration decisions by leaving V22 and V24 untouched.
Do not interpret formatting output or generated build files as permission to change unrelated
source-controlled files.

### Validation

Run in this exact order:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Then run:

```bash
git diff --check -- AGENTS.md README.md docs build.gradle.kts gradle src/main src/test
rg -n 'jdbc:h2|org\.h2|libs\.h2|com\.h2database' \
  build.gradle.kts gradle/libs.versions.toml src/test README.md docs || true
git status --short
```

Manually verify every changed local Markdown link target and anchor. Review the full Gradle output
for Checkstyle warnings, test failures, Javadoc failures, and JaCoCo gate failures.

### Completion criteria

- The exact required Gradle command sequence succeeds without Checkstyle warnings or test,
  Javadoc, formatting, or coverage failures.
- The 10,000-entry ceiling is enforced, tested at and above the boundary, represented in OpenAPI,
  and documented consistently.
- Stable duplicate-name error assertions and diagnostic-safety checks comply with shared testing
  standards.
- H2 is absent and every integration test remains PostgreSQL Testcontainers-backed.
- The final diff contains no migration, API-versioning, sibling-repository, whitespace, or broken
  documentation-link changes outside the requested scope.
