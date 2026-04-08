# Transaction Service

> "Archetype: service. Role: Manages financial transactions and CSV imports."
>
> — [AGENTS.md](AGENTS.md#tree-position)

[![Build](https://github.com/budgetanalyzer/transaction-service/actions/workflows/build.yml/badge.svg)](https://github.com/budgetanalyzer/transaction-service/actions/workflows/build.yml)

Spring Boot microservice for managing financial transactions in Budget Analyzer - a reference architecture for microservices.

## Overview

The Transaction Service is responsible for:

- Managing financial transactions (income, expenses, transfers)
- Tracking accounts and balances
- Importing transactions from CSV files
- Providing transaction history and analytics
- Supporting multi-currency transactions

## Features

- RESTful API for transaction management
- PostgreSQL persistence with Flyway migrations
- OpenAPI/Swagger documentation
- CSV transaction import
- JPA/Hibernate for data access
- Spring Boot Actuator for health checks
- Input validation and error handling

## Technology Stack

- **Java 24**
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

- JDK 24
- Docker and Docker Compose (for infrastructure)

**Local development setup**: See [getting-started.md](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/getting-started.md)

**Database configuration**: See [database-setup.md](https://github.com/budgetanalyzer/orchestration/blob/main/docs/development/database-setup.md)

### Running Locally

```bash
# Start shared infrastructure in the orchestration repo
cd ../orchestration
tilt up

# In another terminal, run the service directly
cd ../transaction-service
export SPRING_DATASOURCE_PASSWORD=your_transaction_database_password
./gradlew bootRun
```

`SPRING_DATASOURCE_USERNAME` already defaults to `transaction_service`, and the
host defaults to `localhost:5432`. If you are reusing values from
`../orchestration/.env`, map `POSTGRES_TRANSACTION_SERVICE_PASSWORD` to
`SPRING_DATASOURCE_PASSWORD`. This service has no RabbitMQ dependency in the
Phase 1 local baseline.

The service runs on port 8082 for development/debugging.

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

# Check code style
./gradlew spotlessCheck

# Apply code formatting
./gradlew clean spotlessApply
```

### Code Quality

This project enforces:
- **Google Java Format** for code style
- **Checkstyle** for standards
- **Spotless** for automated formatting

## Cross-User Search And Counts

`GET /v1/transactions/search` is the paged cross-user transaction search endpoint. It
requires the `transactions:read:any` permission and returns
`PagedResponse<TransactionResponse>` with a stable `content` array and `metadata` object.
The metadata fields are `page`, `size`, `numberOfElements`, `totalElements`, `totalPages`,
`first`, and `last`.

- Default pagination is `page=0` and `size=50`.
- Maximum page size is `100`.
- Default sort is `date,desc` then `id,desc`.
- Supported sort fields are `id`, `ownerId`, `accountId`, `bankName`, `date`,
  `currencyIsoCode`, `amount`, `type`, `description`, `createdAt`, and `updatedAt`.
- Unsupported sort fields are rejected with HTTP `400`.
- Each response item is a `TransactionResponse`, which always includes `ownerId`.

Count behavior is split the same way:

- `GET /v1/transactions/count` always counts only the requesting user's active transactions,
  regardless of whether the caller also holds `transactions:read:any`.
- `GET /v1/transactions/search/count` is the cross-user count endpoint, requires
  `transactions:read:any`, and accepts the same transaction filter fields as the search
  endpoint.

## Project Structure

```
transaction-service/
├── src/
│   ├── main/
│   │   ├── java/              # Java source code
│   │   └── resources/
│   │       ├── application.properties
│   │       └── db/migration/  # Flyway migrations
│   └── test/java/             # Unit and integration tests
└── build.gradle.kts           # Build configuration
```

## Integration

This service integrates with:
- **[Session Gateway](https://github.com/budgetanalyzer/session-gateway)** and **Envoy ext_authz** — Session Gateway manages browser authentication and Redis-backed sessions; Envoy ext_authz validates those sessions and injects pre-validated `X-User-Id`, `X-Roles`, and `X-Permissions` headers before requests reach the service
- **API Gateway** (Envoy + NGINX) for routing and session enforcement
- **PostgreSQL** for data persistence
- **Service Common** for shared utilities (including claims-header security that reads pre-validated headers from ext_authz)
- **[Permission Service](https://github.com/budgetanalyzer/permission-service)** for fine-grained claims-header-based authorization (roles and atomic permissions like `transactions:read`, `transactions:write`)

See the [orchestration repository](https://github.com/budgetanalyzer/orchestration) for full system setup.

## Related Repositories

- **Orchestration**: https://github.com/budgetanalyzer/orchestration
- **Service Common**: https://github.com/budgetanalyzer/service-common
- **Session Gateway**: https://github.com/budgetanalyzer/session-gateway
- **Currency Service**: https://github.com/budgetanalyzer/currency-service
- **Permission Service**: https://github.com/budgetanalyzer/permission-service
- **Web Frontend**: https://github.com/budgetanalyzer/budget-analyzer-web

## License

MIT

## Contributing

This project is currently in early development. Contributions, issues, and feature requests are welcome as we build toward a stable release.
