# Transaction Service - Database Schema

**Status:** Active
**Service:** transaction-service
**Database:** PostgreSQL 16+
**Schema Management:** Flyway migrations

## Overview

This service owns the `budget_analyzer` PostgreSQL database and manages its
tables with Flyway version-controlled migrations. Migrations create unqualified
table names, so local objects live in the connection's default schema
(`public` in the standard orchestration database) unless the runtime
environment explicitly sets a different PostgreSQL search path.

## Schema Discovery

```bash
# View all migrations
ls -l src/main/resources/db/migration/

# Connect to database
POSTGRES_POD=$(kubectl get pods -n infrastructure -l app=postgresql -o jsonpath='{.items[0].metadata.name}')
kubectl exec -it -n infrastructure "$POSTGRES_POD" -- /bin/sh -c \
  'PGPASSWORD="$POSTGRES_TRANSACTION_SERVICE_PASSWORD" psql -U transaction_service -d budget_analyzer'

# List all tables in the active schema
\dt

# List all tables in the default local schema explicitly
\dt public.*

# View table structure
\d public.transaction
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

The database returns only structured duplicate candidates. Description matching
and batch skip/import semantics are service-layer behavior documented in
[Transaction Duplicate Detection](duplicate-detection.md).

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
returns previous import metadata but does not expose `content_hash`. See
[Transaction Duplicate Detection](duplicate-detection.md) for the preview token
and exact-file reupload contract.

Newly created token-backed batch transactions are linked through
`transaction.file_import_id` to either the new `file_import` row or the existing
matching row. `transaction.file_import_id` remains nullable only for legacy or
service-created transactions that did not originate from an uploaded source file.

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

Saved-view criteria, open-ended date behavior, and pinned/excluded membership
rules are documented in [Saved Views](saved-views.md). Migration
`V16__delete_legacy_saved_views.sql` removes rows written with the old
`startDate` and `endDate` criteria JSON shape.

## Migration Strategy

### Flyway Conventions

**Location:** `src/main/resources/db/migration/`

**Naming:** `V{version}__{description}.sql`
- Example: `V18__add_transaction_notes.sql`
- Example: `V19__add_transaction_notes_index.sql`

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
ALTER TABLE transaction
ADD COLUMN notes TEXT;

CREATE INDEX idx_transaction_notes ON transaction USING gin(to_tsvector('english', notes));
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
- `transaction.date` - Business date (DATE)
- `created_at`, `updated_at` - Audit timestamps (TIMESTAMP)

### UUIDs

**Type:** `UUID`
- Used by `saved_view.id`
- Generated by the application through JPA

### Enums

**Storage:** `VARCHAR` with application-level validation
- More flexible than PostgreSQL ENUM
- Easier to modify values
- Validation in Java code

## Indexes

### Query Patterns

**Most common queries:**
1. Get transactions by account and date range
2. Search transactions by bank, currency, amount, type, and description
3. Count or page cross-user transaction search results
4. Resolve owner-scoped duplicate candidates during import
5. List saved views by user

**Index strategy:**
- Index foreign keys and ownership columns (`file_import_id`, `owner_id`,
  `user_id`)
- Index date columns for range queries
- Index frequently filtered transaction columns (`account_id`, `bank_name`,
  `currency_iso_code`, `type`, `deleted`)
- Keep duplicate candidate lookup aligned with the strict financial identity
  fields in [Transaction Duplicate Detection](duplicate-detection.md)

### Performance Monitoring

```sql
-- Find missing indexes
SELECT schemaname, tablename, attname, n_distinct, correlation
FROM pg_stats
WHERE schemaname = 'public'
ORDER BY abs(correlation) DESC;

-- Check index usage
SELECT schemaname, tablename, indexname, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
WHERE schemaname = 'public';
```

## Schema Evolution Guidelines

### Adding Columns

✅ **Safe (backward compatible):**
```sql
ALTER TABLE transaction ADD COLUMN new_field VARCHAR(100);
```

✅ **Safe with default:**
```sql
ALTER TABLE transaction ADD COLUMN status VARCHAR(20) DEFAULT 'ACTIVE';
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
ALTER TABLE transaction ALTER COLUMN description TYPE VARCHAR(1000);

-- Example: Narrowing (check data first!)
ALTER TABLE transaction ALTER COLUMN currency_iso_code TYPE VARCHAR(3);
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
  'PGPASSWORD="$POSTGRES_TRANSACTION_SERVICE_PASSWORD" psql -U transaction_service -d budget_analyzer -c "TRUNCATE TABLE transaction CASCADE;"'
```

## References

- **Domain Model:** [domain-model.md](domain-model.md)
- **Flyway Docs:** https://flywaydb.org/documentation/
- **PostgreSQL Docs:** https://www.postgresql.org/docs/
- **Common Patterns:** [@service-common/docs/advanced-patterns.md](https://github.com/budgetanalyzer/service-common/blob/main/docs/advanced-patterns.md#flyway-migrations)
