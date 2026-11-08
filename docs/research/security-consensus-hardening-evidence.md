# Security Consensus Hardening Evidence

## 2026-11-07: Transaction-service safe runtime defaults

### Logging boundary

Default and `kubernetes` configuration log `org.budgetanalyzer` at `INFO`. HTTP request and
response body flags are absent from the base configuration, so service-common's safe `false`
defaults apply. Body capture and application `TRACE` logging are available only through the
opt-in `debug-logging` profile, which is documented for synthetic local data only.

The dedicated `budgetanalyzer.audit` logger is explicitly enabled at `INFO`, independently of the
root `WARN` threshold. A full-context request test verifies that one authenticated transaction
deletion emits exactly one audit event with the route pattern and actor identifier.

### Readiness and authorization coverage

Health probes are enabled outside Kubernetes as well as in-cluster. Liveness retains only process
state. Readiness includes `readinessState` and PostgreSQL's `db` indicator; RabbitMQ and downstream
services are excluded because transaction request serving does not depend on them.

A full-context mapping test enumerates application handlers from `RequestMappingHandlerMapping`
and rejects every handler lacking method- or class-level `@PreAuthorize`. The explicit exception
set is empty.

### Validation

- `./gradlew test --tests
  org.budgetanalyzer.transaction.TransactionServiceApplicationIntegrationTest` passed all three
  production-configuration tests. They confirmed the effective `kubernetes` profile leaves both
  body flags false, readiness changes from `200` to `503` when PostgreSQL stops, and liveness
  remains `200`.
- The authorization and audit integration tests passed in the full suite. The mapping check found
  no unprotected application handlers, and one authenticated `DELETE` emitted exactly one audit
  event.
- `./gradlew clean spotlessApply` passed.
- Final `./gradlew clean build` passed 482 tests with no failures, errors, skips, or Checkstyle
  warnings. JaCoCo covered 3,478 of 3,825 lines and 1,089 of 1,381 branches, above the configured
  gates. An earlier full run exposed one long test annotation line; moving that property into the
  dynamic test configuration removed the warning before the final run.
- `git diff --check` passed.
