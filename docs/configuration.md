# Configuration

**Status:** Active
**Service:** transaction-service

## Local Development

The service runs on port `8082` by default.

Required local environment:

```bash
export SPRING_DATASOURCE_PASSWORD=your_transaction_database_password
export PREVIEW_IMPORT_TOKEN_ENCRYPTION_SECRET=replace_with_a_long_random_secret
```

`SPRING_DATASOURCE_USERNAME` defaults to `transaction_service`, and the database
host defaults to `localhost:5432`. If you are reusing values from
`../orchestration/.env`, map `POSTGRES_TRANSACTION_SERVICE_PASSWORD` to
`SPRING_DATASOURCE_PASSWORD`.

This service has no RabbitMQ dependency in the Phase 1 local baseline.

## Logging

Application logging defaults to `INFO` for `org.budgetanalyzer`. HTTP metadata logging remains
enabled at `DEBUG`, but request and response body capture use the safe service-common defaults and
are disabled.

The opt-in `debug-logging` Spring profile raises application logging to `TRACE` and enables HTTP
request and response body capture. Use it only for local debugging with synthetic data; never use
it with real financial statements or transaction data.

```bash
SPRING_PROFILES_ACTIVE=debug-logging ./gradlew bootRun
```

Structured state-change audit events are emitted at `INFO` on the dedicated
`budgetanalyzer.audit` logger. Audit events contain route metadata and identifiers, not request or
response bodies.

## Health Probes

Actuator liveness and readiness probe groups are enabled in every environment. Liveness contains
only Spring's `livenessState` indicator. Readiness contains `readinessState` and `db`, because
PostgreSQL is required to serve transaction requests. RabbitMQ and downstream services are not
readiness dependencies.

- `/actuator/health/liveness` reports whether the process should be restarted.
- `/actuator/health/readiness` reports unavailable when PostgreSQL cannot be reached.

## Database Error Details

PostgreSQL server error details are disabled at the JDBC driver boundary. This
prevents Hibernate diagnostics from including database values, such as
user-supplied saved-view names, while preserving SQL states used for safe
exception translation. The setting is applied as a Hikari data-source property
so it remains active when the JDBC URL is overridden.

## Statement Import Uploads

Statement preview uses multipart upload limits from Spring Boot. The service
defaults to `25MB` for both each uploaded file part and the full multipart
request. `max-file-size` is evaluated separately for every repeated `files`
part; `max-request-size` applies to the combined body containing all files and
form fields.

| Environment variable | Property | Required | Default |
| --- | --- | --- | --- |
| `TRANSACTION_IMPORT_MAX_FILE_SIZE` | `spring.servlet.multipart.max-file-size` | No | `25MB` |
| `TRANSACTION_IMPORT_MAX_REQUEST_SIZE` | `spring.servlet.multipart.max-request-size` | No | `25MB` |

Set both values when importing larger bank statement PDFs or grouped previews.
For example, multiple files that are each below `25MB` can still exceed the
default combined `25MB` request limit. When requests go through the gateway,
the gateway body-size limit must be at least as large as the intended combined
request or the client will receive `413 Request Entity Too Large` before the
request reaches transaction-service.

## Preview Import Tokens

Preview import tokens are encrypted by the transaction service. Configure
`PREVIEW_IMPORT_TOKEN_ENCRYPTION_SECRET` with a non-empty text secret before
startup.

The service normalizes the UTF-8 secret with SHA-256 and uses the result as
deterministic AES-GCM key material. Changing the secret invalidates outstanding
preview tokens.

`PREVIEW_IMPORT_TOKEN_TTL` defaults to `PT30M` and accepts any Spring `Duration`
value, such as `PT15M` or `1h`.

| Environment variable | Property | Required | Default |
| --- | --- | --- | --- |
| `PREVIEW_IMPORT_TOKEN_ENCRYPTION_SECRET` | `budgetanalyzer.transaction.preview-import-token.encryption-secret` | Yes | none |
| `PREVIEW_IMPORT_TOKEN_TTL` | `budgetanalyzer.transaction.preview-import-token.ttl` | No | `PT30M` |

See [Transaction Duplicate Detection](duplicate-detection.md#preview-import-token)
for how preview import tokens participate in preview-to-batch import behavior.

## Service-Common Artifacts

Local builds resolve
[service-common](https://github.com/budgetanalyzer/service-common) from
`mavenLocal()`, so GitHub credentials are not required for normal local builds.

Default GitHub Actions `build.yml` runs and release builds resolve the pinned
`serviceCommon` version from GitHub Packages. The full contract is documented in
orchestration:
[service-common artifact resolution](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/service-common-artifact-resolution.md).

Maven Central is used only for external dependencies and explicitly excludes
`org.budgetanalyzer`. Internal artifacts resolve only from Maven Local or the
authenticated `service-common` GitHub Packages repository.

This service imports `org.budgetanalyzer:spring-platform` for shared Spring
dependency management and keeps `org.budgetanalyzer:service-web` explicit for
runtime utilities.
