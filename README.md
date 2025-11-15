# Transaction Service

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
- PostgreSQL database
- Gradle (wrapper included)

### Running Locally

```bash
# Build the service
./gradlew build

# Run the service
./gradlew bootRun
```

The service will start on the default port (8082) and connect to PostgreSQL.

### Configuration

Configure via `application.properties` or environment variables:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/budget_analyzer
spring.datasource.username=budget_analyzer
spring.datasource.password=budget_analyzer
```

### API Documentation

Once running, access the OpenAPI documentation at:
- Swagger UI: `http://localhost:8082/swagger-ui.html`
- OpenAPI Spec: `http://localhost:8082/v3/api-docs`

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
./gradlew spotlessApply
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

See the [orchestration repository](https://github.com/budget-analyzer/orchestration) for full system setup.

## Related Repositories

- **Orchestration**: https://github.com/budget-analyzer/orchestration
- **Service Common**: https://github.com/budget-analyzer/service-common
- **Currency Service**: https://github.com/budget-analyzer/currency-service
- **Web Frontend**: https://github.com/budget-analyzer/budget-analyzer-web

## License

Apache License 2.0

## Contributing

This project is currently in early development. Contributions, issues, and feature requests are welcome as we build toward a stable release.
