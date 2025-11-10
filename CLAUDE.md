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

See [@service-common/CLAUDE.md](../service-common/CLAUDE.md) and [@service-common/docs/](../service-common/docs/) for:
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
cat src/main/java/org/budgetanalyzer/budgetanalyzer/repository/spec/TransactionSpecifications.java
```

See [@src/main/java/org/budgetanalyzer/budgetanalyzer/repository/spec/TransactionSpecifications.java](src/main/java/org/budgetanalyzer/budgetanalyzer/repository/spec/TransactionSpecifications.java)

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

**Transaction Entity:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | Long | Yes | Primary key |
| accountId | String | No | Multi-account support |
| bankName | String | Yes | Bank name from CSV config |
| date | LocalDate | Yes | Transaction date |
| currencyIsoCode | String | Yes | ISO 4217 currency code |
| amount | BigDecimal | Yes | Transaction amount |
| type | TransactionType | Yes | CREDIT or DEBIT |
| description | String | Yes | Transaction description |
| createdAt | Instant | Inherited | Audit timestamp |
| updatedAt | Instant | Inherited | Audit timestamp |
| deleted | Boolean | Inherited | Soft-delete flag |

See [@src/main/java/org/budgetanalyzer/budgetanalyzer/domain/Transaction.java](src/main/java/org/budgetanalyzer/budgetanalyzer/domain/Transaction.java)

### Package Structure

```
org.budgetanalyzer.budgetanalyzer/
├── api/                  # REST controllers and API DTOs
│   ├── request/         # Request DTOs
│   └── response/        # Response DTOs
├── domain/              # JPA entities
├── service/             # Business logic interfaces
│   └── impl/           # Service implementations
├── repository/          # Data access layer
│   └── spec/           # JPA Specifications for search
└── config/             # Spring configuration
```

**Package Dependency Rules:**
```
api → service (NEVER repository)
service → repository
service → domain
repository → domain
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

## Testing Strategy

**Current State:**
- Minimal test coverage - primary opportunity for improvement
- Basic context loading test only
- No controller, service, or repository tests
- No CSV import integration tests

**Test Framework:**
- JUnit 5 (Jupiter)
- H2 in-memory database for testing
- Spring Boot Test (`@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest`)

**Priority Testing Needs:**
1. CSV import integration tests with sample files
2. Transaction search with complex filter combinations
3. Service layer business logic tests
4. Soft-delete behavior verification
5. Error handling and validation scenarios

See [@service-common/docs/testing-patterns.md](../service-common/docs/testing-patterns.md) for testing conventions.

## Future Enhancements

### High Priority
- [ ] **Flyway database migrations** - Currently using JPA `ddl-auto: none`
- [ ] **Comprehensive test coverage** - Service, repository, controller, CSV import tests
- [ ] **Pagination for list/search endpoints** - Currently returns all results
- [ ] **Transaction update endpoint** - Only create/delete currently supported

### Medium Priority
- [ ] **Duplicate transaction detection** - Prevent re-importing same transactions
- [ ] **Redis caching for search results** - Cache frequently queried searches
- [ ] **MapStruct for DTO mapping** - Replace manual mapping
- [ ] **Transaction categorization** - Manual or automatic category assignment
- [ ] **CSV export capability** - Export transactions to CSV

### Low Priority
- [ ] **Prometheus metrics** - Custom business metrics for imports, searches
- [ ] **Distributed tracing** - Zipkin/Jaeger integration
- [ ] **Event publishing** - Kafka/RabbitMQ for transaction CRUD events
- [ ] **GraphQL endpoint** - Alternative to REST API

See [@service-common/docs/advanced-patterns.md](../service-common/docs/advanced-patterns.md) for guidance on implementing:
- Flyway migrations
- Redis caching
- Event-driven messaging

## AI Assistant Guidelines

When working on this service:

### Critical Rules

1. **NEVER implement changes without explicit permission** - Always present a plan and wait for approval
2. **Distinguish between statements and requests** - "I did X" is informational, not a request
3. **Questions deserve answers first** - Provide information before implementing
4. **Wait for explicit action language** - Only implement when user says "do it", "implement", etc.
5. **Limit file access** - Stay within transaction-service directory

### Code Quality

- **Production-quality only** - No shortcuts or workarounds
- **Follow service layer architecture** - Services accept/return entities, not API DTOs
- **Pure JPA only** - No Hibernate-specific imports
- **Controllers NEVER import repositories** - All database access via services
- **Always run before committing:**
  1. `./gradlew clean spotlessApply`
  2. `./gradlew clean build`

### Checkstyle Warnings

- **Read build output carefully** - Check for warnings even if build succeeds
- **Fix all Checkstyle warnings** - Treat as errors
- **Common issues**: Missing Javadoc periods, wildcard imports, line length
- **If unable to fix**: Document warning details and notify user

### Architecture

- **Controllers**: Thin, HTTP-focused, delegate to services
- **Services**: Business logic, validation, transactions, entity retrieval
- **Repositories**: Data access only, used by services only
- **Domain entities**: Pure JPA, no business logic
- **API DTOs**: In `api/request` and `api/response` only

### CSV Import Feature

- **Configuration-driven**: Most banks added via YAML, no code changes
- **Test with real CSV files**: Always validate with bank export samples
- **Error handling**: Provide line numbers and filenames in error messages
- **Transaction boundaries**: Entire import succeeds or rolls back

### Documentation

- **Update CLAUDE.md** when architecture changes
- **Add JavaDoc** with proper punctuation (period at end of first sentence)
- **Document complex CSV mappings** in comments
- **Keep OpenAPI annotations current**
