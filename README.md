# Transaction Service

[![Build](https://github.com/budgetanalyzer/transaction-service/actions/workflows/build.yml/badge.svg)](https://github.com/budgetanalyzer/transaction-service/actions/workflows/build.yml)

> **⚠️ Work in Progress**: This project is under active development. Features and documentation are subject to change.

Spring Boot microservice for managing financial transactions in Budget Analyzer - a personal finance management application.

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
# Build the service
./gradlew build

# Run the service
./gradlew bootRun
```

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
- **API Gateway** (NGINX) for routing
- **PostgreSQL** for data persistence
- **Service Common** for shared utilities

See the [orchestration repository](https://github.com/budgetanalyzer/orchestration) for full system setup.

## Related Repositories

- **Orchestration**: https://github.com/budgetanalyzer/orchestration
- **Service Common**: https://github.com/budgetanalyzer/service-common
- **Currency Service**: https://github.com/budgetanalyzer/currency-service
- **Web Frontend**: https://github.com/budgetanalyzer/budget-analyzer-web

## License

MIT

## Contributing

This project is currently in early development. Contributions, issues, and feature requests are welcome as we build toward a stable release.
