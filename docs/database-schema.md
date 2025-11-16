# Transaction Service - Database Schema

**Status:** Active
**Service:** transaction-service
**Database:** PostgreSQL 16+
**Schema Management:** Flyway migrations

## Overview

This service uses a dedicated schema in the shared PostgreSQL database with Flyway for version-controlled migrations.

## Schema Discovery

```bash
# View all migrations
ls -l src/main/resources/db/migration/

# Connect to database
docker exec -it postgres psql -U budget_analyzer -d budget_analyzer

# List all tables in this service's schema
\dt transaction_service.*

# View table structure
\d transaction_service.transactions
```

## Core Tables

### transactions

**Purpose:** Stores all financial transactions

```sql
CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    transaction_date DATE NOT NULL,
    description VARCHAR(500) NOT NULL,
    category VARCHAR(100),
    transaction_type VARCHAR(10) NOT NULL, -- 'DEBIT' or 'CREDIT'
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transactions_account_id ON transactions(account_id);
CREATE INDEX idx_transactions_date ON transactions(transaction_date);
CREATE INDEX idx_transactions_category ON transactions(category);
```

**Key Columns:**
- `id` - UUID primary key
- `account_id` - Reference to account (foreign key future)
- `amount` - Always positive, type indicates direction
- `currency` - ISO 4217 currency code
- `transaction_date` - Business date (not creation timestamp)
- `transaction_type` - DEBIT (outflow) or CREDIT (inflow)

**Indexes:**
- Primary key on `id`
- Account lookup: `account_id`
- Date range queries: `transaction_date`
- Category filtering: `category`

### budgets

**Purpose:** Stores budget definitions

```sql
CREATE TABLE budgets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    category VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT budgets_date_range CHECK (end_date > start_date)
);

CREATE INDEX idx_budgets_dates ON budgets(start_date, end_date);
CREATE INDEX idx_budgets_category ON budgets(category);
```

**Key Columns:**
- `id` - UUID primary key
- `name` - User-friendly budget name
- `amount` - Budget limit
- `start_date`, `end_date` - Budget period
- `category` - Optional category constraint

**Constraints:**
- `budgets_date_range` - Ensures valid date range

### categories

**Purpose:** Hierarchical category structure

```sql
CREATE TABLE categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    parent_id UUID REFERENCES categories(id),
    category_type VARCHAR(10) NOT NULL, -- 'INCOME' or 'EXPENSE'
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT categories_unique_name_parent UNIQUE(name, parent_id)
);

CREATE INDEX idx_categories_parent_id ON categories(parent_id);
CREATE INDEX idx_categories_type ON categories(category_type);
```

**Key Columns:**
- `id` - UUID primary key
- `name` - Category name (unique within parent)
- `parent_id` - Self-referencing for hierarchy
- `category_type` - INCOME or EXPENSE

**Constraints:**
- `categories_unique_name_parent` - Prevents duplicate names under same parent
- Self-referencing foreign key for hierarchy

## Migration Strategy

### Flyway Conventions

**Location:** `src/main/resources/db/migration/`

**Naming:** `V{version}__{description}.sql`
- Example: `V001__create_transactions_table.sql`
- Example: `V002__add_category_column.sql`

**Discovery:**
```bash
# List all migrations
ls -lh src/main/resources/db/migration/

# View migration history
docker exec postgres psql -U budget_analyzer -d budget_analyzer \
  -c "SELECT * FROM flyway_schema_history;"
```

### Creating Migrations

**Process:**
1. Create new file: `V{next_version}__{description}.sql`
2. Write SQL (CREATE, ALTER, etc.)
3. Test locally
4. Commit with code changes
5. Flyway applies on next service start

**Example:**
```sql
-- V003__add_transaction_notes.sql
ALTER TABLE transactions
ADD COLUMN notes TEXT;

CREATE INDEX idx_transactions_notes ON transactions USING gin(to_tsvector('english', notes));
```

### Rollback Strategy

**Flyway doesn't support automatic rollback.**

**Manual rollback:**
1. Create new migration with reverse changes
2. Example: `V004__remove_transaction_notes.sql`

## Data Types

### Monetary Values

**Type:** `DECIMAL(19,2)`
- Precision: 19 digits total
- Scale: 2 decimal places
- Avoids floating-point rounding errors

### Dates

**Type:** `DATE` for business dates, `TIMESTAMP` for audit trails
- `transaction_date` - Business date (DATE)
- `created_at`, `updated_at` - Audit timestamps (TIMESTAMP)

### UUIDs

**Type:** `UUID`
- PostgreSQL native UUID type
- Generated: `gen_random_uuid()` (PostgreSQL 13+)

### Enums

**Storage:** `VARCHAR` with application-level validation
- More flexible than PostgreSQL ENUM
- Easier to modify values
- Validation in Java code

## Indexes

### Query Patterns

**Most common queries:**
1. Get transactions by account and date range
2. Search transactions by category
3. Find budgets overlapping date range
4. List categories by type

**Index strategy:**
- Index foreign keys (`account_id`, `parent_id`)
- Index date columns for range queries
- Index frequently filtered columns (`category`, `category_type`)

### Performance Monitoring

```sql
-- Find missing indexes
SELECT schemaname, tablename, attname, n_distinct, correlation
FROM pg_stats
WHERE schemaname = 'transaction_service'
ORDER BY abs(correlation) DESC;

-- Check index usage
SELECT schemaname, tablename, indexname, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
WHERE schemaname = 'transaction_service';
```

## Schema Evolution Guidelines

### Adding Columns

✅ **Safe (backward compatible):**
```sql
ALTER TABLE transactions ADD COLUMN new_field VARCHAR(100);
```

✅ **Safe with default:**
```sql
ALTER TABLE transactions ADD COLUMN status VARCHAR(20) DEFAULT 'ACTIVE';
```

### Renaming Columns

⚠️ **Requires coordination:**
1. Add new column
2. Backfill data
3. Update application code
4. Remove old column (separate release)

### Removing Columns

⚠️ **Two-phase process:**
1. Remove column from JPA entity (application deployment)
2. Drop column in database (later migration)

### Changing Types

⚠️ **Risk of data loss - requires careful testing:**
```sql
-- Example: Widening a column (safe)
ALTER TABLE transactions ALTER COLUMN description TYPE VARCHAR(1000);

-- Example: Narrowing (check data first!)
ALTER TABLE transactions ALTER COLUMN currency TYPE VARCHAR(3);
```

## Backup & Recovery

### Local Development

```bash
# Backup
docker exec postgres pg_dump -U budget_analyzer budget_analyzer > backup.sql

# Restore
docker exec -i postgres psql -U budget_analyzer budget_analyzer < backup.sql
```

### Test Data

```bash
# Seed test data
curl -X POST http://localhost:8082/admin/seed-test-data

# Clear all data (DANGEROUS - dev only)
docker exec postgres psql -U budget_analyzer -d budget_analyzer \
  -c "TRUNCATE TABLE transactions CASCADE;"
```

## References

- **Domain Model:** [domain-model.md](domain-model.md)
- **Flyway Docs:** https://flywaydb.org/documentation/
- **PostgreSQL Docs:** https://www.postgresql.org/docs/
- **Common Patterns:** [@service-common/docs/advanced-patterns.md](https://github.com/budgetanalyzer/service-common/blob/main/docs/advanced-patterns.md#flyway-migrations)
