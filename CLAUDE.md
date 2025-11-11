# Transaction Service - Budget and Transaction Management

## Service Purpose

Manages financial transactions and CSV imports for the Budget Analyzer application.

**Domain**: Transaction and budget management
**Responsibilities**:
- CRUD operations for financial transactions
- Multi-bank CSV file import with configurable formats
- Advanced transaction search with dynamic filtering
- Multi-account and multi-currency transaction support

## Spring Boot Patterns

**This service follows standard Budget Analyzer Spring Boot conventions.**

See [@service-common/CLAUDE.md](https://github.com/budget-analyzer/service-common/blob/main/CLAUDE.md) and [@service-common/docs/](https://github.com/budget-analyzer/service-common/tree/main/docs) for:
- Architecture layers (Controller → Service → Repository)
- Naming conventions (`*Controller`, `*Service`, `*ServiceImpl`, `*Repository`)
- Testing patterns (JUnit 5, TestContainers)
- Error handling (exception hierarchy, `BusinessException` vs `InvalidRequestException`)
- Logging conventions (SLF4J structured logging)
- Dependency management (inherit from service-common parent POM)
- Code quality standards (Spotless, Checkstyle, var usage, Javadoc)
- Validation strategy (Bean Validation vs business validation)

## Service-Specific Patterns

### CSV Import System

**The most sophisticated feature of this service** - Configuration-driven CSV parsing for multiple banks.

**Key Capabilities:**
- Multi-bank support via YAML configuration
- Flexible column mapping (different headers per bank)
- Dual amount patterns:
  - Single amount + type column (Capital One)
  - Separate credit/debit columns (Bangkok Bank)
- Multi-file import in single request
- Transactional imports with automatic rollback on errors
- Detailed error reporting with line numbers and filenames

**Currently Supported Banks:**
- Capital One (USD)
- Bangkok Bank (THB) - Two statement format variants
- Truist (USD)

**Discovery:**
```bash
# View all configured CSV formats
cat src/main/resources/application.yml | grep -A 10 "csv-config-map"

# Find CSV import endpoint
grep -r "import" src/main/java/*/api/ | grep "@PostMapping"

# View CSV mapper logic
cat src/main/java/org/budgetanalyzer/budgetanalyzer/service/impl/CsvTransactionMapper.java
```

**Configuration Example:**
```yaml
budget-analyzer:
  csv-config-map:
    capital-one:
      bank-name: "Capital One"
      default-currency-iso-code: "USD"
      credit-header: "Transaction Amount"
      debit-header: "Transaction Amount"
      date-header: "Transaction Date"
      date-format: "MM/dd/uu"
      description-header: "Transaction Description"
      type-header: "Transaction Type"
```

**Adding New Banks:**
1. Add configuration to `application.yml` (see pattern above)
2. Restart service - no code changes needed
3. Test import with sample CSV using format key from config
4. Validate parsed transactions in database

See [@src/main/resources/application.yml](src/main/resources/application.yml) for complete configuration examples.

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

See [@src/main/java/org/budgetanalyzer/transaction/repository/spec/TransactionSpecifications.java](src/main/java/org/budgetanalyzer/transaction/repository/spec/TransactionSpecifications.java)

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

**Standard Spring Boot layered architecture** - See [@service-common/CLAUDE.md](https://github.com/budget-analyzer/service-common/blob/main/CLAUDE.md)

**Service-specific packages:**
- `repository/spec/` - JPA Specifications for advanced search
- `service/impl/` - Includes CSV mapping logic (`CsvTransactionMapper`)

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

See [@service-common/docs/testing-patterns.md](https://github.com/budget-analyzer/service-common/blob/main/docs/testing-patterns.md) for testing conventions.

**Current state**: Minimal coverage, priority areas: CSV import, search filters, soft-delete behavior

## Notes for Claude Code

**General guidance**: See [@service-common/CLAUDE.md](https://github.com/budget-analyzer/service-common/blob/main/CLAUDE.md) for code quality standards and build commands.

**Service-specific reminders**:
- CSV import is configuration-driven (YAML) - most banks need no code changes
- Always test CSV imports with real bank export samples
- JPA Specifications enable dynamic search queries - see `repository/spec/`
