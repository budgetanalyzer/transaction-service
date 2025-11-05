# Budget Analyzer API - Spring Boot Microservice

## Project Overview

The Budget Analyzer API is a production-grade Spring Boot microservice responsible for managing financial transactions and CSV imports within the Budget Analyzer application ecosystem. It provides RESTful APIs for transaction operations, multi-bank CSV file imports, and advanced transaction search capabilities.

## Architecture

### Technology Stack

- **Framework**: Spring Boot 3.5.6
- **Language**: Java 24
- **Database**: PostgreSQL (production), H2 (testing)
- **API Documentation**: SpringDoc OpenAPI 3
- **Build Tool**: Gradle (Kotlin DSL)
- **Code Quality**: Spotless (Google Java Format), Checkstyle

### Package Structure

The service follows a clean, layered architecture with clear separation of concerns:

- **api/**: REST controllers and API-specific request DTOs only
- **domain/**: JPA entities representing business domain
- **service/**: Business logic interfaces
- **service/impl/**: Service implementations and internal mappers
- **repository/**: Data access layer (Spring Data JPA)
- **repository/spec/**: JPA Specifications for dynamic queries
- **config/**: Spring configuration classes

**Package Dependency Rules:**
```
api → service → repository
api → domain
service → domain
service → repository
repository → domain
```

API classes should NEVER be imported by service layer.

## Architectural Principles

### 1. Production-Quality Code

**RULE**: All code must be production-ready. No shortcuts, prototypes, or workarounds.

- Follow established design patterns
- Implement proper error handling and validation
- Write comprehensive tests
- Ensure thread safety where applicable
- Use appropriate logging levels
- Handle edge cases explicitly

### 2. Service Layer Architecture

**RULE**: Services accept and return domain entities, NOT API request/response objects.

This enables service reusability across multiple contexts:
- REST API endpoints
- Scheduled jobs
- Message queue consumers (future)
- Internal service-to-service calls

**Controller Responsibilities:**
- Handle HTTP concerns (status codes, headers)
- Retrieve entities by ID from repositories
- Map API DTOs to/from domain entities
- Delegate business logic to services

**Service Responsibilities:**
- Execute business logic
- Perform business validation
- Manage transactions
- Coordinate between repositories
- Publish domain events

**Example Pattern:**
```java
@RestController
@RequestMapping("/transactions")
public class TransactionController {

  @Autowired private TransactionRepository transactionRepository;
  @Autowired private TransactionService transactionService;

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    // 1. Retrieve entity
    var transaction = transactionRepository.findByIdActive(id)
        .orElseThrow(() -> new ResourceNotFoundException("Transaction not found"));

    // 2. Delegate to service
    transactionService.deleteTransaction(id);

    // 3. Return response
    return ResponseEntity.noContent().build();
  }
}
```

**Controllers ONLY:**
- Use `repository.findById(id)` or `repository.findByIdActive(id)` for entity resolution
- Throw `ResourceNotFoundException` when entity not found
- Throw `InvalidRequestException` for malformed requests

**Controllers NEVER:**
- Perform complex repository queries
- Execute business logic or validation
- Import service-level exceptions beyond ResourceNotFoundException

### 3. Persistence Layer: Pure JPA

**RULE**: Use pure JPA (Jakarta Persistence API) exclusively. NO Hibernate-specific features.

**Why?**
- **Portability**: Allows switching JPA providers without code changes
- **Standard compliance**: JPA is a specification with multiple implementations
- **Architectural discipline**: Maintains flexibility at minimal cost

**Forbidden:**
```java
❌ import org.hibernate.*;
❌ import org.hibernate.annotations.*;
❌ import org.hibernate.criterion.*;
```

**Allowed:**
```java
✅ import jakarta.persistence.*;
```

**Note**: While we acknowledge Hibernate is unlikely to be replaced, adhering to JPA standards is a best practice that prevents vendor lock-in and maintains architectural integrity.

### 4. Clear Package Separation

Package boundaries should be self-evident from inspection. See the **Package Structure** section above for the complete package organization and dependency rules.

### 5. Exception Handling Strategy

**Controller-Level Exceptions:**
- `ResourceNotFoundException` - Entity not found by ID (404)
- `InvalidRequestException` - Malformed request data (400)

**Service-Level Exceptions:**
- `BusinessException` - Business rule violations (422)
- Custom service-specific errors via enum (e.g., `BudgetAnalyzerError`)

**Global Exception Handler:**
All exceptions are handled centrally via `@RestControllerAdvice` in service-common library.

### 6. Validation Strategy

**Bean Validation (Controller Layer):**
```java
@PostMapping("/import")
public List<TransactionResponse> importCsv(
    @RequestParam @NotBlank String format,
    @RequestParam(required = false) String accountId,
    @RequestParam("files") List<MultipartFile> files) {
    // Bean validation automatically applied
}
```

**Business Validation (Service Layer):**
```java
@Service
public class TransactionServiceImpl implements TransactionService {
    @Transactional
    public Transaction createTransaction(Transaction transaction) {
        // Business rules
        validateTransactionFields(transaction);
        checkDuplicates(transaction);

        return transactionRepository.save(transaction);
    }
}
```

### 7. Code Quality Standards

**Spotless Configuration:**
- Google Java Format (1.17.0)
- Automatic import ordering: java → javax → jakarta → org → com → com.bleurubin
- Trailing whitespace removal
- File ends with newline
- Unused import removal

**Checkstyle Enforcement:**
- Version 12.0.1
- Custom rules in `config/checkstyle/checkstyle.xml`
- Enforces Hibernate import ban
- Enforces naming conventions

**Variable Declarations:**
   **Use `var` whenever possible** for local variables to reduce verbosity and improve readability.
      - Prefer `var` whenever possible
      - Use explicit types only when the only other option is to cast a return type, e.g. 
      ```java
         Map<String, Object> details = Map.of("method", "POST", "uri", "/api/users", "status", 201);
         var body = "{\"name\":\"John Doe\"}";
      ```
**Imports:**
  **No wildcard imports, always expand explicit imports**

**Javadoc Comments:**
  **CRITICAL**: All Javadoc comments must follow these formatting rules to pass Checkstyle:

  - **First sentence MUST end with a period (`.`)** - This is enforced by the `SummaryJavadoc` Checkstyle rule
  - The first sentence should be a concise summary (appears in method/class listings)
  - Use proper punctuation throughout

  **Examples:**

  ```java
  // ✅ CORRECT - First sentence ends with period
  /** Converts object to JSON string with sensitive fields masked. */
  public static String toJson(Object object) { }

  /** Header name for correlation ID. */
  public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

  /**
   * Masks a sensitive string value.
   *
   * @param value The value to mask
   * @param showLast Number of characters to show at the end
   * @return Masked value
   */
  public static String mask(String value, int showLast) { }

  // ❌ INCORRECT - Missing period at end of first sentence
  /** Converts object to JSON string with sensitive fields masked */
  public static String toJson(Object object) { }

  /** Header name for correlation ID */
  public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
  ```

  **Key Points:**
  - Single-line Javadoc: `/** Summary sentence here. */`
  - Multi-line Javadoc: First line after `/**` must end with period
  - Field documentation: Even short descriptions need periods
  - Always end the summary sentence with a period, even if it's obvious

**Build Commands:**

**IMPORTANT**: Always use these two commands in sequence. Never use other gradle commands like `check`, `bootJar`, `checkstyleMain`, etc.

```bash
# 1. Format code (always run first)
./gradlew spotlessApply

# 2. Build and test (always run second)
./gradlew clean build
```

The `clean build` command will:
- Clean previous build artifacts
- Compile all source code
- Run Spotless checks
- Run Checkstyle
- Run all unit and integration tests
- Build the JAR file

## Service Features

### Transaction Management

- Create single or multiple transactions
- Retrieve transactions by ID
- Soft-delete transactions (never permanently removed)
- Advanced search with dynamic filtering via JPA Specifications
- Multi-account support via optional `accountId` field
- Multi-currency support with ISO 4217 currency codes

### CSV Import System

**The most sophisticated feature of this service:**

- **Multi-bank support**: Configurable CSV formats for different banks
- **Flexible parsing**: Handles varied column names, date formats, and amount structures
- **Dual amount patterns**:
  - Single amount column + type column (e.g., Capital One)
  - Separate credit/debit columns (e.g., Bangkok Bank)
- **Multi-file import**: Process multiple CSV files in a single request
- **Transactional imports**: Automatic rollback on parsing errors
- **Detailed error reporting**: Line numbers and filenames in error messages

**Supported Banks:**
- Capital One (USD)
- Bangkok Bank (THB) - Two statement format variants
- Truist (USD)

**Configuration-driven design** - Add new banks without code changes via YAML configuration.

### Soft Delete Pattern

Transactions are never permanently deleted from the database:
- Delete operations mark records with `deleted=true`
- All queries automatically exclude deleted records via `findByIdActive()` and `findAllActive()`
- Inherited from `SoftDeletableEntity` base class (service-common)
- Provides data retention and audit trail capabilities

### Advanced Search

**JPA Specification-based dynamic queries** with support for:
- Exact match: id, type
- Case-insensitive LIKE: accountId, bankName, description
- Exact match (case-insensitive): currencyIsoCode
- Range queries: date (dateFrom/dateTo), amount (minAmount/maxAmount)
- Timestamp filtering: createdAfter, createdBefore, updatedAfter, updatedBefore

All search filters are optional and combinable.

## Configuration

### Application Properties

Configuration is externalized via `application.yml` and bound to type-safe `@ConfigurationProperties` classes:

```java
@ConfigurationProperties(prefix = "budget-analyzer")
public record BudgetAnalyzerProperties(@Valid Map<String, CsvConfig> csvConfigMap) {}
```

### CSV Format Configuration

**The heart of the CSV import system** - supports multiple bank CSV formats:

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

    bkk-bank:
      bank-name: "Bangkok Bank"
      default-currency-iso-code: "THB"
      credit-header: "Credit"
      debit-header: "Debit"
      date-header: "Date"
      date-format: "d MMM uuuu HH:mm"
      description-header: "Description"
```

**Key Configuration Fields:**
- `bank-name`: Display name for the bank
- `default-currency-iso-code`: ISO 4217 currency code (e.g., USD, THB)
- `date-header`: Column name for transaction date
- `date-format`: Java DateTimeFormatter pattern
- `description-header`: Column name for description
- `credit-header` / `debit-header`: Amount column names
- `type-header`: Optional column for explicit transaction type

### Environment-Specific Configuration

- Development: `application.yml`
- Testing: `application-test.yml`
- Production: Environment variables or external config server

## Database Schema

### Design Principles

- Pure JPA entity definitions
- Database-agnostic SQL where possible
- Proper indexing for query performance
- Foreign key constraints for data integrity
- Soft-delete support via base entity class

### Transaction Entity

**Location:** [domain/Transaction.java](src/main/java/com/bleurubin/budgetanalyzer/domain/Transaction.java)

Extends `SoftDeletableEntity` from `service-common` dependency.

**IMPORTANT**: When modifying the Transaction entity, update this documentation section to reflect schema changes.

Properties:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | Long | Yes | Primary key (auto-generated) |
| accountId | String | No | Account identifier for multi-account support |
| bankName | String | Yes | Name of the bank (from CSV config) |
| date | LocalDate | Yes | Transaction date |
| currencyIsoCode | String | Yes | ISO 4217 currency code |
| amount | BigDecimal | Yes | Transaction amount |
| type | TransactionType | Yes | CREDIT or DEBIT |
| description | String | Yes | Transaction description |
| createdAt | Instant | Inherited | Timestamp when created |
| updatedAt | Instant | Inherited | Timestamp when last updated |
| deleted | Boolean | Inherited | Soft-delete flag |

### Transaction Management

- `@Transactional` at service layer only
- Read-only transactions for queries (implicit via JpaRepository)
- Proper isolation levels for critical operations
- Rollback on runtime exceptions

## Testing Strategy

### Current Test Coverage

**Minimal** - Primary opportunity for improvement:
- Basic context loading test only
- No controller, service, or repository layer tests
- No CSV import integration tests
- No error handling validation tests

### Test Infrastructure

- **Unit Tests**: JUnit 5 (Jupiter)
- **Database**: H2 in-memory for testing
- **Spring Test**: `@SpringBootTest` available

### Testing TODO (High Priority)

**Unit Tests Needed:**
- Service layer: Mock repositories, test business logic
- Repository layer: Use `@DataJpaTest` with H2
- Controller layer: Use `@WebMvcTest`, mock services
- CSV mapping: Test CsvTransactionMapper edge cases

**Integration Tests Needed:**
- End-to-end CSV import tests with sample files
- Transaction search with complex filters
- Error handling and validation scenarios
- Soft-delete behavior verification

### Test Coverage Goals

- Minimum 80% code coverage
- 100% coverage for critical business logic
- All edge cases explicitly tested

## API Documentation

### OpenAPI/Swagger

- Accessible at `/swagger-ui.html` (development)
- Automatic generation from annotations
- Comprehensive endpoint documentation
- Example request/response payloads

### API Versioning

- Current version: v1 (explicit URL-based: `/v1/...`)
- Gateway prefix: `/api` handled by NGINX API Gateway
- External URLs: `/api/v1/transactions`
- Internal service URLs: `/v1/transactions`
- Context path: `/budget-analyzer-api` for service identification
- Future versions: URL-based (`/v2/...`, routed as `/api/v2/...` externally)
- Backward compatibility maintained for 2 major versions

## Deployment

### Docker

```dockerfile
# Multi-stage build
FROM eclipse-temurin:24-jdk as builder
WORKDIR /app
COPY . .
RUN ./gradlew bootJar

FROM eclipse-temurin:24-jre
COPY --from=builder /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Kubernetes

- Deployment manifests in orchestration repository
- Health checks: `/actuator/health`
- Readiness probes: `/actuator/health/readiness`
- Liveness probes: `/actuator/health/liveness`

### Environment Variables

**Application:**
- `SPRING_PROFILES_ACTIVE`: Active Spring profile

**Database:**
- `POSTGRES_HOST`: Database host
- `POSTGRES_PORT`: Database port
- `POSTGRES_DB`: Database name
- `POSTGRES_USER`: Database username
- `POSTGRES_PASSWORD`: Database password

## Service Dependencies

### Required Services

- **PostgreSQL** database (production)

### Optional Integrations

- NGINX API Gateway (for routing)
- Prometheus (metrics)
- Zipkin/Jaeger (distributed tracing)

## Development Workflow

### Local Development

1. **Prerequisites:**
   - JDK 24
   - PostgreSQL 15+
   - Gradle 8.11+

2. **Start Services:**
   ```bash
   cd ../budget-analyzer
   docker compose up
   ```
   This starts PostgreSQL shared across all microservices. Each service has its own predefined database.

3. **Run Application:**
   ```bash
   ./gradlew bootRun
   ```

4. **Access Swagger UI:**
   http://localhost:8082/swagger-ui.html

### Code Formatting

**Before committing:**
```bash
./gradlew spotlessApply
./gradlew clean build
```

### Git Workflow

- Create feature branches from `main`
- Follow conventional commits
- Run all checks before pushing
- Request code review for all changes
- 
## Testing Strategy

### Unit Tests

**Current Coverage:**
- Limited test coverage (opportunity for improvement)

**Test Framework:**
- JUnit 5 (Jupiter)
- Spring Boot Test (for integration testing if needed)

**Running Tests:**
```bash
./gradlew test
```

### Testing Guidelines

**What to Test:**
- CSV parsing with various formats
- Exception handler response formatting
- Soft-delete lifecycle behavior
- Auditable entity timestamp management
- Safe logger sanitization logic

**Test Coverage Goals:**
- Minimum 80% code coverage
- 100% coverage for critical utilities
- All edge cases explicitly tested


### Testing Philosophy: Test Correct Behavior, Not Defects

**CRITICAL PRINCIPLE**: Tests must always be written for how components **should** behave according to their specification and real-world requirements, NOT around defective implementations.

**When Tests Fail Due to Implementation Issues:**

1. **STOP immediately** - Do not attempt to work around the implementation deficiency
2. **Analyze the failure** - Determine if the test is correct or if the implementation is deficient
3. **Explain the situation** to the user:
    - What behavior the test expects (correct behavior)
    - What the current implementation does (deficient behavior)
    - Why the implementation is inadequate
4. **Ask the user** how to proceed:
    - Option A: Fix the implementation to match correct behavior
    - Option B: Remove/modify tests if requirements have changed

**Example Scenario:**

```
User: Add tests for CSV parsing with mismatched column counts

Assistant discovers:
- Tests written for correct behavior (handle rows with fewer/more columns than headers)
- Implementation fails because it assumes all rows have same column count as headers
- This is a common real-world edge case that MUST be handled

Assistant response:
"I've written tests for handling mismatched column counts (a common real-world scenario),
but they're failing because the current OpenCsvParser implementation doesn't handle this
edge case. The implementation assumes all rows have exactly the same number of columns
as headers, which causes ArrayIndexOutOfBoundsException.

Would you like me to:
1. Fix the implementation to handle mismatched columns properly, or
2. Remove these tests (not recommended - this is a real-world edge case)"
```

**Why This Matters:**

- **Test integrity**: Tests document correct behavior and serve as specifications
- **Code quality**: Working around bugs creates technical debt
- **Maintainability**: Future developers need accurate tests, not workarounds
- **Real-world robustness**: Edge cases are often discovered in production if not tested

**Anti-Pattern to Avoid:**

```java
// ❌ WRONG - Writing tests around broken implementation
@Test
void shouldThrowExceptionWhenRowHasFewerColumns() {
    // Documenting current buggy behavior instead of fixing it
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> {
        parser.parse("Name,Age\nJohn");
    });
}
```

**Correct Pattern:**

```java
// ✅ CORRECT - Writing test for correct behavior
@Test
void shouldHandleRowsWithFewerColumnsThanHeaders() {
    var result = parser.parse("Name,Age\nJohn");
    // Correct behavior: missing columns should be empty strings
    assertEquals("", result.get(0).get("Age"));
}
// If this fails, FIX THE IMPLEMENTATION, don't change the test
```


## Best Practices

### General

1. **Follow SOLID principles**: Single Responsibility, Open/Closed, Liskov Substitution, Interface Segregation, Dependency Inversion
2. **Favor composition over inheritance**
3. **Program to interfaces, not implementations**
4. **Use dependency injection** for all dependencies
5. **Avoid static methods** except for pure utility functions
6. **Immutability**: Use final fields where possible
7. **Null safety**: Use Optional for potentially null returns

### Spring Boot Specific

1. **Use constructor injection** over field injection
2. **Avoid @Autowired on fields** - use constructor injection
3. **Keep controllers thin** - delegate to services
4. **Use @Transactional only at service layer**
5. **Leverage Spring Boot starters** for consistent configuration
6. **Use @ConfigurationProperties** for type-safe configuration
7. **Implement proper health checks** via Actuator

### Database

1. **Always use JPA specifications** for dynamic queries
2. **Avoid N+1 queries** - use JOIN FETCH appropriately
3. **Index foreign keys** and frequently queried columns
4. **Use optimistic locking** (`@Version`) where appropriate
5. **Never expose entities directly** in API responses
6. **Use projections** for read-heavy operations

### Security

1. **Validate all inputs** at controller layer
2. **Sanitize user-provided data**
3. **Never log sensitive information**
4. **Use HTTPS** in production
5. **Implement rate limiting** for public endpoints
6. **Audit sensitive operations**

### Performance

1. **Use pagination** for list endpoints
2. **Implement caching** where appropriate
3. **Use async processing** for long-running operations
4. **Monitor query performance**
5. **Use connection pooling** (HikariCP default)

## Common Tasks

### Adding a New Entity

1. Create entity class in `domain/` package
2. Create repository interface in `repository/`
3. Create service interface in `service/`
4. Create service implementation in `service/impl/`
5. Create controller in `api/`
6. Create request/response DTOs in `api/request/` and `api/response/`
7. Create mapper interface (MapStruct - future)
8. Write unit tests for all layers
9. Update OpenAPI documentation

### Adding a New Bank CSV Format

1. **Update Configuration**: Add new bank config to `application.yml`:
   ```yaml
   budget-analyzer:
     csv-config-map:
       my-new-bank:
         bank-name: "My New Bank"
         default-currency-iso-code: "USD"
         # ... other fields
   ```
2. **Test Configuration**: Restart application and verify configuration loading via startup logs
3. **Prepare Test CSV**: Get sample CSV export from the bank
4. **Test Import**: Use `/v1/transactions/import` endpoint with format parameter matching config key
5. **Validate Results**: Check database for correctly parsed transactions
6. **Handle Edge Cases**: Update `CsvTransactionMapper` if special handling needed

**Note**: Most banks can be added via configuration alone without code changes.

### Adding a Scheduled Job

1. Create scheduler class in `scheduler/` package
2. Use `@Scheduled` annotation
3. Configure scheduling properties
4. Implement idempotent processing
5. Add monitoring/logging
6. Implement distributed locking with ShedLock (if multi-instance deployment)

## Troubleshooting

### Common Issues

**Application won't start:**
- Check database connectivity
- Verify all required environment variables set
- Review application logs for stack traces

**Database connection errors:**
- Verify PostgreSQL is running
- Check credentials and host/port
- Ensure database exists

**Build failures:**
- Run `./gradlew clean build`
- Check for Spotless formatting violations
- Review Checkstyle errors
- **If encountering "cannot resolve" errors for service-common classes** (e.g., `SoftDeletableEntity`, `SafeLogger`, `ApiErrorResponse`):
  - Navigate to service-common directory: `cd /workspace/service-common`
  - Publish latest artifact: `./gradlew clean build publishToMavenLocal`
  - Return to budget-analyzer-api directory: `cd /workspace/budget-analyzer-api`
  - Retry the build: `./gradlew clean build`

**Checkstyle errors:**
Review `config/checkstyle/checkstyle.xml` rules and fix violations including warnings if possible.

**Tests failing:**
- Clear test database: `./gradlew cleanTest test`
- Check for port conflicts
- Review test logs

**CSV import failures:**
- Verify format parameter matches config key
- Check CSV headers match configuration
- Review application logs for detailed error with line numbers
- Validate date format matches CSV data

## Notes for Claude Code

When working on this project:

### Critical Rules

1. **NEVER implement changes without explicit permission** - Always present a plan and wait for approval
2. **Distinguish between informational statements and action requests** - If the user says "I did X", they're informing you, not asking you to do it
3. **Questions deserve answers, not implementations** - Respond to questions with information, not code changes
4. **Wait for explicit implementation requests** - Only implement when the user says "implement", "do it", "make this change", or similar action-oriented language
5. **Limit file access to the current directory and below** - Don't read or write files outside of the current budget-analyzer-api directory

### Code Quality

- **All code must be production-quality** - No shortcuts, prototypes, or workarounds
- **Follow service layer architecture** - Services accept/return entities, not API DTOs
- **Use pure JPA only** - No Hibernate-specific imports or annotations
- **Maintain package separation** - Clear boundaries between api, service, repository, domain
- **Always run these commands before committing:**
  1. `./gradlew spotlessApply` - Format code
  2. `./gradlew clean build` - Build and test everything

### Checkstyle Warning Handling

**CRITICAL**: When verifying the build with `./gradlew clean build`, always pay attention to Checkstyle warnings.

**Required Actions:**
1. **Always read build output carefully** - Look for Checkstyle warnings even if the build succeeds
2. **Attempt to fix all Checkstyle warnings** - Treat warnings as errors that need immediate resolution
3. **Common Checkstyle issues to watch for:**
    - Javadoc missing periods at end of first sentence
    - Missing Javadoc comments on public methods/classes
    - Import statement violations (wildcard imports, Hibernate imports)
    - Line length violations
    - Naming convention violations
    - Indentation issues
4. **If unable to fix warnings:**
    - Document the specific warning message
    - Explain why it cannot be fixed
    - Notify the user immediately with the warning details
    - Provide the file path and line number where the warning occurs
5. **Never ignore warnings** - Even if the build passes, Checkstyle warnings indicate code quality issues that must be addressed

**Example Response Pattern:**
```
Build completed successfully, but found Checkstyle warnings:
- File: src/main/java/com/bleurubin/service/Example.java:42
- Issue: Javadoc comment missing period at end of first sentence
- Action: Fixed by adding period to Javadoc summary

OR

Build completed with Checkstyle warnings that I cannot resolve:
- File: src/main/java/com/bleurubin/service/Example.java:42
- Warning: [specific warning message]
- Reason: [explanation of why it cannot be fixed]
```

### Architecture Conventions

- Controllers: Thin, HTTP-focused, delegate to services
- Services: Business logic, validation, transactions
- Repositories: Data access only
- Domain entities: Pure JPA, no business logic
- API DTOs: In `api/request` and `api/response` packages only, never used by services

### Testing Requirements

- Write tests for all new features
- Maintain minimum 80% coverage
- Test edge cases explicitly
- Use proper test doubles (mocks, stubs, fakes)

### Documentation

- Update this file when architecture changes
- Add JavaDoc for public APIs
- Document complex business logic
- Keep OpenAPI annotations current

## Future Enhancements

### Planned 📋

#### High Priority - Testing & Quality
- [ ] **Add comprehensive unit tests** - Service, repository, and controller layer tests; currently only smoke test exists
- [ ] **Add CSV import integration tests** - End-to-end tests with sample CSV files from each supported bank
- [ ] **Add transaction search tests** - Test all filter combinations and edge cases in JPA specifications
- [ ] **Add error handling tests** - Validate exception handling for invalid CSVs, missing files, malformed data
- [ ] **Implement test containers for integration tests** - Replace H2 with PostgreSQL test containers for realistic integration testing

#### High Priority - Database Management
- [ ] **Add Flyway for database migrations** - Version-controlled schema evolution; currently using JPA ddl-auto: none
- [ ] **Create baseline migration** - Document current schema as V1__initial_schema.sql
- [ ] **Add indexes for search queries** - Index frequently searched columns (date, accountId, bankName, currencyIsoCode)

#### High Priority - API Enhancements
- [ ] **Add pagination to transaction list and search endpoints** - Currently returns all results; need Page/Pageable support
- [ ] **Add transaction update endpoint** - Currently only create/delete supported; update operation stubbed
- [ ] **Add bulk delete endpoint** - Delete multiple transactions in single request

#### Medium Priority - Resilience & Features
- [ ] **Implement MapStruct for DTO mapping** - Replace manual mapping with compile-time safe DTO transformations
- [ ] **Add duplicate transaction detection** - Prevent importing same transaction multiple times (by date, amount, description)
- [ ] **Add transaction categorization** - Support manual or automatic categorization of transactions
- [ ] **Add CSV export capability** - Export transactions back to CSV format

#### Medium Priority - Observability
- [ ] **Add Prometheus metrics** - Custom business metrics for imports, search queries, error rates
- [ ] **Implement distributed tracing** - Add Zipkin/Jaeger for request flow visibility across microservices
- [ ] **Implement request/response logging filter** - Comprehensive HTTP request/response logging for debugging and audit
- [ ] **Add audit logging for data changes** - Track who changed what and when using JPA entity listeners

#### Medium Priority - Data Management
- [ ] **Add Redis caching for search results** - Cache frequently queried transaction searches
- [ ] **Implement hard delete capability** - Admin endpoint to permanently remove soft-deleted transactions
- [ ] **Add transaction reconciliation** - Compare imported transactions against expected balances

#### Low Priority - Optional Features
- [ ] **Add GraphQL endpoint (optional)** - Provide GraphQL API alongside REST for flexible querying
- [ ] **Implement event publishing (Kafka/RabbitMQ)** - Publish domain events for transaction CRUD to enable event-driven architecture
- [ ] **Add real-time CSV validation endpoint** - Validate CSV format without importing
- [ ] **Add scheduled cleanup job** - Purge soft-deleted transactions older than retention period
- [ ] **Add support for CSV templates** - Generate template CSV files for each bank format
- [ ] **Implement API rate limiting** - Protect against abuse with request throttling (may be handled at gateway level)

## Support and Contact

For questions or issues:
- Review this documentation first
- Check existing GitHub issues
- Create new issue with detailed description and reproduction steps
