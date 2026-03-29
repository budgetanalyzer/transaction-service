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

## Code Exploration

NEVER use Agent/subagent tools for code exploration. Use Grep, Glob, and Read directly.

## Documentation Discipline

Always keep documentation up to date after any configuration or code change.

Update the nearest affected documentation in the same work:
- `AGENTS.md` when instructions, guardrails, discovery commands, or repository-specific workflow changes
- `README.md` when setup, usage, or repository purpose changes
- `docs/` when architecture, configuration, APIs, behaviors, or operational workflows change

Do not leave documentation updates as follow-up work.

## Service Purpose

Manages financial transactions and CSV imports for the Budget Analyzer application.

**Domain**: Transaction and budget management
**Responsibilities**:
- CRUD operations for financial transactions
- Multi-bank CSV file import with configurable formats
- Advanced transaction search with dynamic filtering
- Multi-account and multi-currency transaction support

## Coding Standards

**Before writing or modifying any Java code, read [code-quality-standards.md](../service-common/docs/code-quality-standards.md).** Do not skip this step. The most common violations: missing `var`, wildcard imports, abbreviated variable names, Javadoc without trailing periods.

## Spring Boot Patterns

**This service follows standard Budget Analyzer Spring Boot conventions.** Uses layered architecture (Controller → Service → Repository) with dependency injection, declarative transactions, and JPA for data access.

**When to consult service-common documentation:**
- **Implementing new features** → Read [service-common/AGENTS.md](../service-common/AGENTS.md) for architecture patterns
- **Handling errors** → Read [error-handling.md](../service-common/docs/error-handling.md) for exception hierarchy
- **Writing tests** → Read [testing-patterns.md](../service-common/docs/testing-patterns.md) for JUnit 5 + TestContainers conventions
- **Code quality issues** → Read [code-quality-standards.md](../service-common/docs/code-quality-standards.md) for Spotless, Checkstyle, var usage

**Quick reference:**
- Naming: `*Controller`, `*Service`, `*Repository`
- Exceptions: Use `BusinessException` for business rule violations, `InvalidRequestException` for bad input
- Logging: SLF4J with structured logging (never log sensitive data)
- Validation: Bean Validation (@Valid) for request DTOs, business validation in service layer
- Dependencies: Inherit from service-common parent POM

### Authorization

All endpoints are protected by fine-grained claims-header-based permissions. Envoy ext_authz validates sessions and injects `X-User-Id`, `X-Permissions`, `X-Roles` headers. `ClaimsHeaderSecurityConfig` (from service-common) extracts these into the Spring Security context. Controllers enforce access via `@PreAuthorize` annotations. Tests use `ClaimsHeaderTestBuilder` to set up per-request authentication.

See [permission-service/AGENTS.md](../permission-service/AGENTS.md) for the RBAC model, role definitions, and permission details. See also the [Permission Service README](../permission-service/README.md) for an overview.

## Service-Specific Patterns

### CSV Import System

**The most sophisticated feature of this service** - Configuration-driven CSV parsing for multiple banks.

**Pattern**: Database-driven format configuration via `statement_format` table. Supports two amount patterns: single column with type indicator (Capital One, Truist) or separate credit/debit columns (Bangkok Bank). Also supports PDF statement extraction.

**When to consult documentation:**
- **Adding new bank formats** → Read [CSV Import Guide](docs/csv-import.md) for configuration examples and step-by-step instructions
- **Troubleshooting import errors** → Read [Troubleshooting section](docs/csv-import.md#troubleshooting) for common issues
- **Understanding amount patterns** → Read [Amount Column Patterns](docs/csv-import.md#amount-column-patterns)

**Quick reference:**
- Currently supported: Capital One (PDF), Bangkok Bank (CSV format)
- Configuration: `statement_format` table (see `StatementFormatService`)
- API: `GET /v1/statement-formats` to list formats, `POST` to create new formats
- Endpoint: `POST /v1/transactions/import`
- Multi-file support with transactional rollback
- No code changes needed for new CSV banks

**Discovery:**
```bash
# View statement format entity
cat src/main/java/org/budgetanalyzer/transaction/domain/StatementFormat.java

# View format service
cat src/main/java/org/budgetanalyzer/transaction/service/StatementFormatService.java
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

### Admin Transaction Search

**ADMIN role required.** Cross-user transaction search and count for admin users, exposed via `AdminTransactionController`.

**Endpoints:**
- `GET /v1/admin/transactions` — Paginated search across all users (default sort: `date`, `id` DESC)
- `GET /v1/admin/transactions/count` — Count matching transactions across all users

**Admin-only filter field:**
- `ownerId` — Filter by the owning user's ID. Part of `TransactionFilter` but only effective on admin endpoints; ignored on user-scoped endpoints where the authenticated user is always applied.

**Sort fields:** `id`, `ownerId`, `accountId`, `bankName`, `date`, `currencyIsoCode`, `amount`, `type`, `description`, `createdAt`, `updatedAt`

**Authorization:** Class-level `@PreAuthorize("hasRole('ADMIN')")` — requires the `ADMIN` role from the `X-Roles` claims header.

**Response:** `AdminTransactionResponse` extends the standard transaction response with the `ownerId` field.

**Discovery:**
```bash
# View admin controller
cat src/main/java/org/budgetanalyzer/transaction/api/AdminTransactionController.java

# View admin response DTO
cat src/main/java/org/budgetanalyzer/transaction/api/response/AdminTransactionResponse.java
```

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
- Transactions: `GET/PATCH/DELETE /v1/transactions/**`
- Count: `GET /v1/transactions/count`
- Preview: `POST /v1/transactions/preview`
- Batch Import: `POST /v1/transactions/batch`
- Bulk Delete: `POST /v1/transactions/bulk-delete`
- Admin Search: `GET /v1/admin/transactions` (ADMIN role)
- Admin Count: `GET /v1/admin/transactions/count` (ADMIN role)
- Saved Views: `/v1/views/**`
- Statement Formats: `/v1/statement-formats/**`

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

# View statement format migration (seed data)
cat src/main/resources/db/migration/V7__add_statement_format.sql

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

## NOTES FOR AI AGENTS

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
- For code quality standards and build commands, see [service-common/AGENTS.md](../service-common/AGENTS.md)

## Honest Discourse

Do not over-validate ideas. The user wants honest pushback, not agreement.

- If something seems wrong, say so directly
- Distinguish "novel" from "obvious in retrospect"
- Push back on vague claims — ask for concrete constraints
- Don't say "great question" or "that's a really interesting point"
- Skip the preamble and caveats — just answer

---

## External Links (GitHub Web Viewing)

*The relative paths in this document are optimized for Claude Code. When viewing on GitHub, use these links to access other repositories:*

- [Service-Common Repository](https://github.com/budgetanalyzer/service-common)
- [Service-Common AGENTS.md](https://github.com/budgetanalyzer/service-common/blob/main/AGENTS.md)
- [Error Handling Documentation](https://github.com/budgetanalyzer/service-common/blob/main/docs/error-handling.md)
- [Testing Patterns Documentation](https://github.com/budgetanalyzer/service-common/blob/main/docs/testing-patterns.md)
- [Code Quality Standards](https://github.com/budgetanalyzer/service-common/blob/main/docs/code-quality-standards.md)
- [Session Gateway Repository](https://github.com/budgetanalyzer/session-gateway)
- [Session Gateway AGENTS.md](https://github.com/budgetanalyzer/session-gateway/blob/main/AGENTS.md)
- [Token Validation Service Repository](https://github.com/budgetanalyzer/token-validation-service)
- [Permission Service Repository](https://github.com/budgetanalyzer/permission-service)
- [Permission Service AGENTS.md](https://github.com/budgetanalyzer/permission-service/blob/main/AGENTS.md)
