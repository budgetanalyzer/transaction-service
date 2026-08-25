# Timezone-Safe Saved-View Audit Timestamps Plan

Align the `saved_view` audit columns with the `Instant` domain contract and make the saved-view
integration coverage portable across JVM timezones. Add a forward-only Flyway migration that
changes `created_at` and `updated_at` from PostgreSQL `TIMESTAMP` to
`TIMESTAMP(6) WITH TIME ZONE`, retain `CURRENT_TIMESTAMP` defaults and nullability, and keep the
public API and saved-view business behavior unchanged.

Existing timezone-free values will receive one simple, deterministic interpretation: treat their
stored wall-clock value as UTC during the type conversion. This matches the intended `Instant`
semantics and avoids environment-dependent migration results. Do not add heuristics, per-row
timezone recovery, compatibility columns, or a separate historical backfill. Historical Flyway
migrations remain immutable.

## Phase 1: Correct the Saved-View Timestamp Schema

### Workspace

.

### Goal

Make both saved-view audit columns timezone-aware PostgreSQL instants through a deterministic,
forward-only migration, with migration coverage and owner documentation matching the final schema.

### Scope

Add the next Flyway migration; verify the pre-migration-to-post-migration conversion against real
PostgreSQL; preserve column nullability, precision, and defaults; update the active database and
domain documentation; and run the repository's focused and full validation gates.

### Non-goals

Changing saved-view membership, timestamp-touching rules, service or HTTP contracts; changing the
Java `Instant` fields; rewriting V4 or V22; reconstructing the original timezone of historical
rows; introducing a runtime compatibility path; changing sibling repositories; or forcing the
application, Gradle, PostgreSQL, or the host to use a particular default timezone.

### Required context

Read `AGENTS.md`, `docs/database-schema.md`, `docs/domain-model.md`, the complete ordered
`src/main/resources/db/migration/` history, `SavedView.java`, `SavedViewRepository.java`,
`SavedViewSchemaMigrationTest.java`, and `SavedViewServiceIntegrationTest.java`. Re-read the shared
Spring conventions, Java quality standards, and testing patterns required by `AGENTS.md` before
modifying Java or tests. Confirm Docker/Testcontainers and the configured Java toolchain are
available before starting implementation.

### Execution steps

1. Add `V23__make_saved_view_timestamps_timezone_aware.sql`. Alter both `saved_view.created_at` and
   `saved_view.updated_at` to `TIMESTAMP(6) WITH TIME ZONE`, using an explicit
   `AT TIME ZONE 'UTC'` conversion so the result does not depend on the migration connection's
   session timezone. Preserve `NOT NULL` and `DEFAULT CURRENT_TIMESTAMP` and do not edit historical
   migrations.
2. Refactor `SavedViewSchemaMigrationTest` so its V22 cutover assertions stop at V22, then add a
   distinct V23 migration test that migrates through V22, inserts a saved view with known
   timezone-free audit values, runs V23, and asserts both columns report
   `timestamp with time zone`, remain non-null with their defaults, and represent the expected UTC
   instants after conversion. Exercise the migration with a non-UTC PostgreSQL session timezone so
   an implicit, environment-dependent cast would fail the test.
3. Verify the unchanged `SavedView.createdAt` and `SavedView.updatedAt` `Instant` mappings validate
   and round-trip against the new schema. Do not add converters, duplicated timestamp fields, or
   global Hibernate timezone configuration unless a concrete focused test proves the standard
   `Instant`/PostgreSQL mapping is insufficient.
4. Update the `saved_view` definition and migration notes in `docs/database-schema.md` to show
   `TIMESTAMP(6) WITH TIME ZONE`. Update `docs/domain-model.md` only as needed to state that the
   existing `Instant` audit contract is now backed by timezone-aware database columns. Do not
   document a historical-timezone recovery guarantee.
5. Run the focused migration and saved-view persistence tests, inspect the complete ordered
   migration history and final diff, then run the mandatory formatting and full-build sequence.

### Implementation notes

Use a single deterministic conversion such as `created_at AT TIME ZONE 'UTC'` and the equivalent
for `updated_at`; this preserves the wall-clock fields as the UTC instants the application intended
without pretending to know a historical local timezone. PostgreSQL stores `WITH TIME ZONE` values
as instants, so no timezone column or per-user offset is needed. Keep both audit columns at
microsecond precision to match the service's other PostgreSQL instant columns. Assert schema
metadata as well as values: a value-only test could pass while the columns silently remain
timezone-free. Do not delete saved views merely to simplify the migration because the direct type
conversion is small and deterministic.

### Validation

Run:

```bash
./gradlew test --tests '*SavedViewSchemaMigrationTest'
./gradlew test --tests '*SavedViewServiceIntegrationTest'
./gradlew clean spotlessApply
./gradlew clean build
git diff --check -- AGENTS.md README.md docs src
```

Inspect the full build output and fix every Checkstyle warning even if Gradle exits successfully.
Also verify V23 is the only historical-schema addition and that the active schema documentation
matches its final column types.

### Completion criteria

V23 deterministically converts both saved-view audit columns to microsecond-precision
`TIMESTAMP WITH TIME ZONE`; migration coverage proves type, constraints, defaults, and UTC value
interpretation against PostgreSQL; the Java `Instant` mapping and saved-view integration suite
pass; owner documentation matches the schema; and the mandatory full build succeeds.

## Phase 2: Lock In Cross-Timezone Portability

### Workspace

.

### Goal

Prove the saved-view timestamp behavior and its integration-test fixture produce the same instants
when the JVM does not run in UTC, preventing the original five-test failure pattern from returning.

### Scope

Audit the direct JDBC timestamp setup in `SavedViewServiceIntegrationTest`, make its intended
instant semantics explicit if necessary, run focused saved-view tests under UTC and a non-UTC JVM
timezone, and repeat the mandatory full validation after any changes.

### Non-goals

Pinning all builds to UTC; relaxing exact timestamp assertions; replacing `Instant` with local
date/time types; changing saved-view membership semantics; broad timestamp refactoring of other
tables; or adding speculative clock abstractions.

### Required context

Re-read `AGENTS.md`, the completed V23 migration and migration coverage, `SavedView.java`,
`SavedViewRepository.java`, `SavedViewService.java`, `SavedViewServiceIntegrationTest.java`, and the
shared testing patterns and Java quality standards. Review the Phase 1 validation output before
changing the test fixture.

### Execution steps

1. Run `SavedViewServiceIntegrationTest` once with a UTC JVM timezone and once with a materially
   different timezone such as `America/New_York`; confirm the five exact timestamp assertions now
   observe the same `Instant` in both runs.
2. Replace the test helper's ambiguous `java.sql.Timestamp` binding with an explicitly
   timezone-aware JDBC value such as a UTC `OffsetDateTime` if the current binding still relies on
   the JVM default timezone. Keep the exact equality assertions: they protect the rule that no-op
   membership deltas and transaction-driven membership cleanup do not touch a view timestamp.
3. Keep or extend a focused persistence assertion that writes and reads a known instant while the
   JVM timezone is non-UTC. Ensure any temporary global timezone change is isolated and restored;
   do not make unrelated tests depend on execution order or mutate shared Spring beans.
4. Review the diff for attempts to mask the defect through Gradle JVM arguments, application
   configuration, assertion tolerances, or local-time conversions. Remove those workarounds and
   retain the schema-plus-timezone-aware-binding solution.
5. Run the affected focused tests under both timezones, then run the mandatory formatting and full
   build in the normal environment. Update owner documentation only if this phase changes a
   documented runtime guarantee.

### Implementation notes

The regression signal is exact: the sentinel `2020-01-01T00:00:00Z` must never reappear as a value
shifted by the JVM offset. Prefer `OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)` or an
equivalent supported PostgreSQL JDBC binding over converting an instant to a local wall-clock
value. Do not weaken `isEqualTo` to a tolerance or compare only local fields, because the business
rule under test is that the persisted instant remains unchanged. The schema migration test remains
the authority that prevents a future return to `TIMESTAMP WITHOUT TIME ZONE`.

### Validation

Run focused tests in fresh Gradle invocations so each test worker receives the intended timezone:

```bash
TZ=UTC ./gradlew test --tests '*SavedViewServiceIntegrationTest' --rerun-tasks
TZ=America/New_York ./gradlew test --tests '*SavedViewServiceIntegrationTest' --rerun-tasks
./gradlew test --tests '*SavedViewSchemaMigrationTest' --rerun-tasks
./gradlew clean spotlessApply
./gradlew clean build
git diff --check -- AGENTS.md README.md docs src
```

Inspect the full build output for Checkstyle warnings and confirm the non-UTC run no longer reports
the five failures at `assertViewTimestamp`.

### Completion criteria

The saved-view schema, Hibernate mapping, direct JDBC test setup, and exact timestamp assertions all
use instant semantics; the focused service suite passes in UTC and `America/New_York`; no global
UTC pin or weakened assertion masks the behavior; documentation remains accurate; and the final
mandatory full build succeeds.
