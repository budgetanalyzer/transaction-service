# Transaction Service - Budget and Transaction Management

## Tree Position

**Archetype**: service
**Scope**: budgetanalyzer ecosystem
**Role**: Manages financial transactions and CSV imports

### Relationships
- **Consumes**: service-common (patterns)
- **Coordinated by**: orchestration
- **Peers with**: Discover via `ls /workspace/*-service`
- **Observed by**: architecture-conversations

### Permissions
- **Read**: `../service-common/`, `../orchestration/docs/`
- **Write**: This repository only

### Discovery
```bash
# My peers
ls -d /workspace/*-service
# My platform
ls ../service-common/
```

## Service Purpose

Manages financial transactions and CSV imports for the Budget Analyzer application.

**Domain**: Transaction and budget management
**Responsibilities**:
- CRUD operations for financial transactions
- Multi-bank CSV file import with configurable formats
- Advanced transaction search with dynamic filtering
- Multi-account and multi-currency transaction support

## Spring Boot Patterns

**This service follows standard Budget Analyzer Spring Boot conventions.** Uses layered architecture (Controller → Service → Repository) with dependency injection, declarative transactions, and JPA for data access.

**When to consult service-common documentation:**
- **Implementing new features** → Read [service-common/CLAUDE.md](../service-common/CLAUDE.md) for architecture patterns
- **Handling errors** → See [error-handling.md](../service-common/docs/error-handling.md) for exception hierarchy
- **Writing tests** → See [testing-patterns.md](../service-common/docs/testing-patterns.md) for JUnit 5 + TestContainers conventions
- **Code quality issues** → See [code-quality-standards.md](../service-common/docs/code-quality-standards.md) for Spotless, Checkstyle, var usage

**Quick reference:**
- Naming: `*Controller`, `*Service`, `*ServiceImpl`, `*Repository`
- Exceptions: Use `BusinessException` for business rule violations, `InvalidRequestException` for bad input
- Logging: SLF4J with structured logging (never log sensitive data)
- Validation: Bean Validation (@Valid) for request DTOs, business validation in service layer
- Dependencies: Inherit from service-common parent POM

## Service-Specific Patterns

### CSV Import System

**The most sophisticated feature of this service** - Configuration-driven CSV parsing for multiple banks.

**Pattern**: YAML-based format configuration eliminates code changes when adding new banks. Supports two amount patterns: single column with type indicator (Capital One, Truist) or separate credit/debit columns (Bangkok Bank).

**When to consult documentation:**
- **Adding new bank formats** → Read [CSV Import Guide](docs/csv-import.md) for configuration examples and step-by-step instructions
- **Troubleshooting import errors** → See [Troubleshooting section](docs/csv-import.md#troubleshooting) for common issues
- **Understanding amount patterns** → Review [Amount Column Patterns](docs/csv-import.md#amount-column-patterns)

**Quick reference:**
- Currently supported: Capital One, Bangkok Bank (2 formats), Truist
- Configuration: `application.yml` under `budget-analyzer.csv-config-map`
- Endpoint: `POST /v1/transactions/import`
- Multi-file support with transactional rollback
- No code changes needed for new banks

**Discovery:**
```bash
# View configured formats
cat src/main/resources/application.yml | grep -A 10 "csv-config-map"

# Find CSV mapper
cat src/main/java/org/budgetanalyzer/budgetanalyzer/service/impl/CsvTransactionMapper.java
```

### Advanced Transaction Search

**JPA Specification-based dynamic queries** with combinable filters:

**Search Criteria:**
- Exact match: `id`, `type`
- Case-insensitive LIKE: `accountId`, `bankName`, `description`
- Case-insensitive exact: `currencyIsoCode`
- Range queries: `dateFrom`/`dateTo`, `minAmount`/`maxAmount`
- Timestamp filtering: `createdAfter`, `createdBefore`, `updatedAfter`, `updatedBefore`

**Discovery:**
```bash
# Find search endpoint
grep -r "search" src/main/java/*/api/ | grep "@GetMapping"

# View JPA specifications
cat src/main/java/org/budgetanalyzer/transaction/repository/spec/TransactionSpecifications.java
```

See [TransactionSpecifications.java](src/main/java/org/budgetanalyzer/transaction/repository/spec/TransactionSpecifications.java)

### Soft Delete Pattern

Transactions are never permanently deleted:
- Delete operations mark records with `deleted=true`
- Queries automatically exclude deleted records via `findByIdActive()`
- Inherited from `SoftDeletableEntity` base class (service-common)
- Provides data retention and audit trail

**Discovery:**
```bash
# Find soft delete methods
grep -r "findByIdActive\|findAllActive" src/main/java/*/repository/
```

### Domain Model

**Key Concept:**
- **Transaction**: Financial transaction with multi-account, multi-currency support, and soft-delete pattern

**Discovery:**
```bash
# View entity
cat src/main/java/org/budgetanalyzer/transaction/domain/Transaction.java

# Find all enums
find src/main/java -name "*.java" -path "*/domain/*" -exec grep -l "^enum " {} \;
```

### Package Structure

**Standard Spring Boot layered architecture:** Controller → Service → Repository with domain entities and DTOs.

**Service-specific packages:**
- `api/` - REST controllers and request/response DTOs
- `service/` - Business logic interfaces and implementations
- `repository/` - JPA repositories and custom queries
- `repository/spec/` - JPA Specifications for advanced search
- `service/impl/` - Includes CSV mapping logic (`CsvTransactionMapper`)
- `domain/` - JPA entities and enums

**Discovery:**
```bash
# View structure
tree src/main/java/org/budgetanalyzer/transaction -L 2

# Or without tree
find src/main/java/org/budgetanalyzer/transaction -type d | sort
```

## API Documentation

**OpenAPI Specification:** Run service and access Swagger UI:
```bash
./gradlew bootRun
# Visit: http://localhost:8082/swagger-ui.html
```

**Key Endpoints:**
- Transaction CRUD: `/v1/transactions/**`
- CSV Import: `/v1/transactions/import`
- Search: `/v1/transactions/search`

**Gateway Access:**
- Internal: `http://localhost:8082/v1/transactions`
- External (via NGINX): `http://localhost:8080/api/v1/transactions`

## Running Locally

**Prerequisites:**
- JDK 24
- PostgreSQL 15+
- Gradle 8.11+

**Start Infrastructure:**
```bash
cd ../orchestration
docker compose up
```

**Run Service:**
```bash
./gradlew bootRun
```

**Access:**
- Service: http://localhost:8082
- Swagger UI: http://localhost:8082/swagger-ui.html
- Health Check: http://localhost:8082/actuator/health

## Discovery Commands

```bash
# Find all REST endpoints
grep -r "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" src/main/java/*/api/

# View CSV configuration
cat src/main/resources/application.yml | grep -A 20 "csv-config-map"

# Check service dependencies
./gradlew dependencies | grep "org.budgetanalyzer"

# View application configuration
cat src/main/resources/application.yml
```

## Build and Test

**Format code:**
```bash
./gradlew clean spotlessApply
```

**Build and test:**
```bash
./gradlew clean build
```

The build includes:
- Spotless code formatting checks
- Checkstyle rule enforcement
- All unit and integration tests
- JAR file creation

**Troubleshooting:**

If encountering "cannot resolve" errors for service-common classes:
```bash
cd ../service-common
./gradlew clean build publishToMavenLocal
cd ../transaction-service
./gradlew clean build
```

## Testing

**Standard testing approach:** JUnit 5 with TestContainers for integration tests, MockMvc for controller tests, Mockito for unit tests.

**When to consult testing documentation:**
- **Writing new tests** → Read [testing-patterns.md](../service-common/docs/testing-patterns.md)
- **Debugging test failures** → See testing-patterns.md for container lifecycle, test data setup

**Quick reference:**
- Test naming: `*Test` (unit), `*IntegrationTest` (with TestContainers)
- Use `@SpringBootTest` + TestContainers for repository/service integration tests
- Use `@WebMvcTest` for isolated controller tests
- Test data: Use builders or test fixtures for domain objects

**Current state**: Minimal coverage, priority areas: CSV import, search filters, soft-delete behavior

## Notes for Claude Code

**CRITICAL - Prerequisites First**: Before implementing any plan or feature:
1. Check for prerequisites in documentation (e.g., "Prerequisites: service-common Enhancement")
2. If prerequisites are NOT satisfied, STOP immediately and inform the user
3. Do NOT attempt to hack around missing prerequisites - this leads to broken implementations that must be deleted
4. Complete prerequisites first, then return to the original task

**Service-specific reminders:**
- CSV import is configuration-driven (YAML) - most banks need no code changes
- Always test CSV imports with real bank export samples
- JPA Specifications enable dynamic search queries - see `repository/spec/`
- Use soft-delete pattern - never hard delete transactions
- For code quality standards and build commands, see [service-common/CLAUDE.md](../service-common/CLAUDE.md)

---

## External Links (GitHub Web Viewing)

*The relative paths in this document are optimized for Claude Code. When viewing on GitHub, use these links to access other repositories:*

- [Service-Common Repository](https://github.com/budgetanalyzer/service-common)
- [Service-Common CLAUDE.md](https://github.com/budgetanalyzer/service-common/blob/main/CLAUDE.md)
- [Error Handling Documentation](https://github.com/budgetanalyzer/service-common/blob/main/docs/error-handling.md)
- [Testing Patterns Documentation](https://github.com/budgetanalyzer/service-common/blob/main/docs/testing-patterns.md)
- [Code Quality Standards](https://github.com/budgetanalyzer/service-common/blob/main/docs/code-quality-standards.md)

## Web Search Protocol

BEFORE any WebSearch tool call:
1. Read `Today's date` from `<env>` block
2. Extract the current year
3. Use current year in queries about "latest", "best", "current" topics
4. NEVER use previous years unless explicitly searching historical content

FAILURE MODE: Training data defaults to 2023/2024. Override with `<env>` year.

## Conversation Capture

When the user asks to save this conversation, write it to `/workspace/architecture-conversations/conversations/` following the format in INDEX.md.
