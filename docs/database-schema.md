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
POSTGRES_POD=$(kubectl get pods -n infrastructure -l app=postgresql -o jsonpath='{.items[0].metadata.name}')
kubectl exec -it -n infrastructure "$POSTGRES_POD" -- /bin/sh -c \
  'PGPASSWORD="$POSTGRES_TRANSACTION_SERVICE_PASSWORD" psql -U transaction_service -d budget_analyzer'

# List all tables in this service's schema
\dt transaction_service.*

# View table structure
\d transaction_service.transaction
```

## Core Tables

### transaction

**Purpose:** Stores all financial transactions

```sql
CREATE TABLE transaction (
    id BIGSERIAL PRIMARY KEY,
    account_id VARCHAR(255),
    bank_name VARCHAR(255) NOT NULL,
    date DATE NOT NULL,
    currency_iso_code VARCHAR(3) NOT NULL,
    amount NUMERIC(38, 2) NOT NULL,
    type VARCHAR(20) NOT NULL,
    description TEXT NOT NULL,
    owner_id VARCHAR(50) NOT NULL,
    file_import_id BIGINT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE,
    deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX idx_transaction_account_id ON transaction(account_id);
CREATE INDEX idx_transaction_bank_name ON transaction(bank_name);
CREATE INDEX idx_transaction_date ON transaction(date);
CREATE INDEX idx_transaction_currency_iso_code ON transaction(currency_iso_code);
CREATE INDEX idx_transaction_type ON transaction(type);
CREATE INDEX idx_transaction_deleted ON transaction(deleted);
CREATE INDEX idx_transaction_owner_id ON transaction(owner_id);
CREATE INDEX idx_transaction_file_import_id ON transaction(file_import_id);
CREATE INDEX idx_transaction_owner_deleted_duplicate_candidates
    ON transaction (
        owner_id,
        deleted,
        account_id,
        bank_name,
        date,
        amount,
        type,
        currency_iso_code
    );
```

**Key Columns:**
- `id` - BIGSERIAL primary key
- `account_id` - Optional account identifier
- `bank_name` - Bank where the transaction occurred
- `date` - Business date (not creation timestamp)
- `amount` - Always positive, type indicates direction
- `currency_iso_code` - ISO 4217 currency code
- `type` - DEBIT (outflow) or CREDIT (inflow)
- `description` - Bank-provided transaction description
- `owner_id` - User that owns the transaction
- `file_import_id` - File import source for token-backed batch imports; nullable
  only for legacy or service-created transactions without an uploaded source
- `deleted` - Soft-delete marker

**Indexes:**
- Primary key on `id`
- Single-column indexes for account, bank, date, currency, type, deleted, owner,
  and file import lookups
- `idx_transaction_owner_deleted_duplicate_candidates` supports owner-scoped
  duplicate candidate lookup across `account_id`, `bank_name`, `date`,
  `amount`, `type`, and `currency_iso_code`. It replaced the exact-description
  duplicate index in migration `V17__replace_duplicate_candidate_index.sql`.

Duplicate detection treats empty `account_id` values as equivalent to `NULL` in
the lookup query. Only active rows (`deleted = false`) for the same `owner_id`
are considered duplicates.

### file_import

**Purpose:** Tracks imported source files by content hash and user.

```sql
CREATE TABLE file_import (
    id BIGSERIAL PRIMARY KEY,
    content_hash VARCHAR(64) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    format VARCHAR(50) NOT NULL,
    account_id VARCHAR(255),
    file_size_bytes BIGINT NOT NULL,
    transaction_count INTEGER NOT NULL,
    imported_by VARCHAR(50) NOT NULL,
    imported_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX idx_file_import_hash_user
    ON file_import(content_hash, imported_by);
CREATE INDEX idx_file_import_imported_at ON file_import(imported_at);
```

**Key Columns:**
- `content_hash` - SHA-256 hash of the uploaded file bytes
- `original_filename` - Filename supplied with the import
- `format` - Statement format key used for parsing
- `account_id` - Optional account identifier supplied during import
- `transaction_count` - Number of transactions recorded for the import
- `imported_by` - User that imported the file
- `imported_at` - Import completion timestamp

Preview uses `content_hash` and `imported_by` to populate the file-level
`fileImport` status for exact file reuploads by the authenticated user. The API
returns previous import metadata but does not expose `content_hash`.

Batch import requires a valid `previewImportToken` from the preview endpoint.
The token carries the source-file identity verified during preview, including
the content hash, original filename, format, account ID, file size, and owner.
When at least one transaction is created, the service records source-file
identity in `file_import`. If the same `(content_hash, imported_by)` already
exists, the batch is not rejected and no duplicate `file_import` row is created;
transaction duplicate rules remain authoritative.

Newly created token-backed batch transactions are linked through
`transaction.file_import_id` to either the new `file_import` row or the existing
matching row. `transaction.file_import_id` remains nullable only for legacy or
service-created transactions that did not originate from an uploaded source
file. If duplicate filtering leaves no rows to create, the batch fails with
`BATCH_IMPORT_NO_TRANSACTIONS_CREATED` and no file import row is recorded.

### saved_view

**Purpose:** Stores user-defined transaction views and their pinned or excluded
transaction overrides.

```sql
CREATE TABLE saved_view (
    id UUID PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    criteria TEXT NOT NULL,
    open_ended BOOLEAN NOT NULL DEFAULT false,
    pinned_ids TEXT NOT NULL DEFAULT '[]',
    excluded_ids TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_saved_view_user_id ON saved_view(user_id);
```

**Key Columns:**
- `criteria` - Saved-view filter JSON using the current `dateFrom` and `dateTo`
  date field names
- `pinned_ids` - JSON array of transaction IDs pinned into the view
- `excluded_ids` - JSON array of transaction IDs excluded from the view
- `open_ended` - Allows the view to ignore the upper date bound when resolving
  memberships

Migration `V16__delete_legacy_saved_views.sql` removes rows written with the old
`startDate` and `endDate` criteria JSON shape. Pinned and excluded IDs are
stored on the same row, so no child tables require cascading.

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
POSTGRES_POD=$(kubectl get pods -n infrastructure -l app=postgresql -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n infrastructure "$POSTGRES_POD" -- /bin/sh -c \
  'PGPASSWORD="$POSTGRES_TRANSACTION_SERVICE_PASSWORD" psql -U transaction_service -d budget_analyzer -c "SELECT * FROM flyway_schema_history;"'
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
POSTGRES_POD=$(kubectl get pods -n infrastructure -l app=postgresql -o jsonpath='{.items[0].metadata.name}')

# Backup
kubectl exec -n infrastructure "$POSTGRES_POD" -- /bin/sh -c \
  'PGPASSWORD="$POSTGRES_TRANSACTION_SERVICE_PASSWORD" pg_dump -U transaction_service budget_analyzer' > backup.sql

# Restore
kubectl exec -i -n infrastructure "$POSTGRES_POD" -- /bin/sh -c \
  'PGPASSWORD="$POSTGRES_TRANSACTION_SERVICE_PASSWORD" psql -U transaction_service -d budget_analyzer' < backup.sql
```

### Test Data

```bash
# Seed test data
curl -X POST http://localhost:8082/admin/seed-test-data

# Clear all data (DANGEROUS - dev only)
POSTGRES_POD=$(kubectl get pods -n infrastructure -l app=postgresql -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n infrastructure "$POSTGRES_POD" -- /bin/sh -c \
  'PGPASSWORD="$POSTGRES_TRANSACTION_SERVICE_PASSWORD" psql -U transaction_service -d budget_analyzer -c "TRUNCATE TABLE transactions CASCADE;"'
```

## References

- **Domain Model:** [domain-model.md](domain-model.md)
- **Flyway Docs:** https://flywaydb.org/documentation/
- **PostgreSQL Docs:** https://www.postgresql.org/docs/
- **Common Patterns:** [@service-common/docs/advanced-patterns.md](https://github.com/budgetanalyzer/service-common/blob/main/docs/advanced-patterns.md#flyway-migrations)
