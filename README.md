# Transaction Service

> "Archetype: service. Role: Manages financial transactions and file-based imports."
>
> — [AGENTS.md](AGENTS.md#tree-position)

[![Build](https://github.com/budgetanalyzer/transaction-service/actions/workflows/build.yml/badge.svg)](https://github.com/budgetanalyzer/transaction-service/actions/workflows/build.yml)

Spring Boot microservice for managing financial transactions in Budget Analyzer - a reference architecture for microservices.

## Overview

The Transaction Service is responsible for:

- Managing financial transactions
- Importing transactions from CSV and PDF statement files
- Supporting multi-account and multi-currency transaction records
- Providing saved transaction views and advanced search

## Features

- RESTful API for transaction management
- PostgreSQL persistence with Flyway migrations
- OpenAPI/Swagger documentation
- CSV and PDF transaction import
- Duplicate detection for preview-to-batch imports
- Saved transaction views with pinned and excluded rows
- JPA/Hibernate for data access
- Spring Boot Actuator for health checks
- Input validation and error handling

## Technology Stack

- **Java 25**
- **Spring Boot 3.x**
  - Spring Web (REST APIs)
  - Spring Data JPA (database access)
  - Spring Boot Actuator (monitoring)
  - Spring Validation
- **PostgreSQL** (database)
- **Flyway** (database migrations)
- **SpringDoc OpenAPI** (API documentation)
- **JUnit 5** (testing)

## Quick Start

### Prerequisites

- JDK 25
- Docker and Docker Compose (for infrastructure)

**Local development setup**: See [getting-started.md](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/getting-started.md)

**Database configuration**: See [database-setup.md](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/database-setup.md)

**Service configuration**: See [Configuration](docs/configuration.md) for local
environment variables, preview import token settings, and service-common
artifact resolution.

### Running Locally

```bash
# Start shared infrastructure in the orchestration repo
cd ../orchestration
tilt up

# In another terminal, run the service directly
cd ../transaction-service
export SPRING_DATASOURCE_PASSWORD=your_transaction_database_password
export PREVIEW_IMPORT_TOKEN_ENCRYPTION_SECRET=replace_with_a_long_random_secret
./gradlew bootRun
```

The service runs on port 8082 for development/debugging. See
[Configuration](docs/configuration.md) for environment variable details.

### API Access

**Production/User access** (through gateway):
- Transactions API: `http://localhost:8080/api/v1/transactions`
- Unified API Documentation: `https://api.budgetanalyzer.localhost/api/docs`
- OpenAPI JSON: `https://api.budgetanalyzer.localhost/api/docs/openapi.json`
- OpenAPI YAML: `https://api.budgetanalyzer.localhost/api/docs/openapi.yaml`

**Development access** (direct to service):
- Swagger UI: `http://localhost:8082/swagger-ui.html`
- OpenAPI Spec: `http://localhost:8082/v3/api-docs`
- Health Check: `http://localhost:8082/actuator/health`

## Development

### Building

```bash
# Clean and build
./gradlew clean build

# Run tests
./gradlew test

# Run tests and generate JaCoCo coverage reports
./gradlew test jacocoTestReport

# Check code style
./gradlew spotlessCheck

# Apply code formatting
./gradlew clean spotlessApply
```

Coverage reports are written to `build/reports/jacoco/test/html/index.html` and
`build/reports/jacoco/test/jacocoTestReport.xml`. `check` enforces the Phase 2
coverage gates: 80% line coverage and 75% branch coverage. The recorded baseline
is 85.16% line / 78.59% branch; ratchet after targeted CSV import, search, and
soft-delete tests.

### Code Quality

This project enforces:
- **Google Java Format** for code style
- **Checkstyle** for standards
- **Spotless** for automated formatting

## API Documentation

Full endpoint reference, request/response examples, and authentication details
are in [docs/api/](docs/api/README.md).

Focused docs:

- [Configuration](docs/configuration.md)
- [Statement Import System](docs/statement-import.md)
- [Transaction Duplicate Detection](docs/duplicate-detection.md)
- [Saved Views](docs/saved-views.md)
- [Database Schema](docs/database-schema.md)
- [Domain Model](docs/domain-model.md)

## Project Structure

```
transaction-service/
├── src/
│   ├── main/
│   │   ├── java/              # Java source code
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/  # Flyway migrations
│   └── test/java/             # Unit and integration tests
└── build.gradle.kts           # Build configuration
```

## Integration

This service integrates with:
- **[Session Gateway](https://github.com/budgetanalyzer/session-gateway)** and **Envoy ext_authz** — Session Gateway manages browser authentication and Redis-backed sessions; Envoy ext_authz validates those sessions and injects pre-validated `X-User-Id`, `X-Roles`, and `X-Permissions` headers before requests reach the service
- **API Gateway** (Envoy + NGINX) for routing and session enforcement
- **PostgreSQL** for data persistence
- **[Service Common](https://github.com/budgetanalyzer/service-common)** for the shared Spring platform and runtime utilities (including claims-header security that reads pre-validated headers from ext_authz)
- **[Permission Service](https://github.com/budgetanalyzer/permission-service)** for fine-grained claims-header-based authorization (roles and atomic permissions like `transactions:read`, `transactions:write`)

See the [orchestration repository](https://github.com/budgetanalyzer/orchestration) for full system setup.

## Related Repositories

- [Orchestration](https://github.com/budgetanalyzer/orchestration) — infrastructure, Tilt, and deployment
- [Service Common](https://github.com/budgetanalyzer/service-common) — shared Spring platform and runtime libraries
- [Session Gateway](https://github.com/budgetanalyzer/session-gateway) — browser authentication and session management
- [Currency Service](https://github.com/budgetanalyzer/currency-service) — exchange rates and currency data
- [Permission Service](https://github.com/budgetanalyzer/permission-service) — roles and fine-grained permissions
- [Web Frontend](https://github.com/budgetanalyzer/budget-analyzer-web) — browser UI

## License

MIT

## Contributing

This project is currently in early development. Contributions, issues, and feature requests are welcome as we build toward a stable release.
