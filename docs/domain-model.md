# Transaction Service - Domain Model

**Status:** Active
**Service:** transaction-service

## Overview

This document summarizes the current domain entities owned by the transaction
service. Schema-level details live in [Database Schema](database-schema.md);
endpoint contracts live in [API Documentation](api/README.md).

## Core Entities

### Transaction

**Purpose:** Represents a single financial transaction owned by a user.

**Key Attributes:**

- `id` (`Long`) - Database-generated transaction identifier.
- `ownerId` (`String`) - Authenticated user that owns the transaction.
- `accountId` (`String`) - Optional account identifier supplied by the client or
  import flow.
- `bankName` (`String`) - Bank where the transaction occurred.
- `date` (`LocalDate`) - Business date of the transaction.
- `currencyIsoCode` (`String`) - ISO currency code.
- `amount` (`BigDecimal`) - Positive transaction amount.
- `type` (`TransactionType`) - `DEBIT` or `CREDIT`.
- `description` (`String`) - Bank-provided transaction description.
- `fileImport` (`FileImport`) - Optional source file record for token-backed
  batch imports.

**Business Rules:**

- Transactions are soft-deleted through `SoftDeletableEntity`.
- Queries for normal user workflows exclude soft-deleted rows.
- Duplicate detection is owner-scoped and documented in
  [Transaction Duplicate Detection](duplicate-detection.md).

### FileImport

**Purpose:** Tracks uploaded source files that produced batch-imported
transactions.

**Key Attributes:**

- `id` (`Long`) - Database-generated file import identifier.
- `contentHash` (`String`) - SHA-256 hash of the uploaded file bytes.
- `originalFilename` (`String`) - Filename supplied in the multipart upload.
- `statementFormatId` (`Long`) - Statement format selected for the import.
- `parserRevisionId` (`Long`) - Parser revision that parsed the import.
- `accountId` (`String`) - Optional account ID applied during import.
- `fileSizeBytes` (`Long`) - Uploaded file size.
- `transactionCount` (`Integer`) - Number of transactions linked to the import.
- `importedBy` (`String`) - User that imported the file.
- `importedAt` (`Instant`) - Import completion timestamp.

**Business Rules:**

- Exact-file reupload detection is scoped by `(contentHash, importedBy)`.
- The API exposes prior import metadata but never exposes `contentHash`.
- Created token-backed batch transactions link to either the new file import row
  or an existing matching row.

### StatementFormat

**Purpose:** Stores the user-facing saved statement format selected for file
imports.

**Key Attributes:**

- `id` (`Long`) - Database-generated statement format identifier.
- `formatType` (`FormatType`) - `CSV`, `PDF`, or `XLSX`.
- `bankName` (`String`) - Bank name assigned to imported transactions.
- `defaultCurrencyIsoCode` (`String`) - Default currency for parsed rows.
- `displayName` (`String`) - UI-friendly format label.
- `scope` (`StatementFormatScope`) - `SYSTEM` for built-in formats or `USER`
  for user-created formats.
- `ownerId` (`String`) - Owner ID for user-scoped formats; null for system
  formats.
- `enabled` (`boolean`) - Whether the format is available for use.

**Business Rules:**

- CSV formats are configuration-driven and can usually be added without code
  changes.
- Parser details are hidden in `ParserRevision` rows. CSV formats use
  serialized column mapping config; static PDF formats use internal handler
  keys.
- Preview, get, and update requests use `StatementFormat.id` as the public
  identity.
- Import setup and examples are documented in
  [Statement Import System](statement-import.md).

### ParserRevision

**Purpose:** Stores deterministic parser configuration or static extractor
routing for a statement format.

**Key Attributes:**

- `id` (`Long`) - Database-generated parser revision identifier.
- `statementFormat` (`StatementFormat`) - Parent format visible to users.
- `revisionNumber` (`Integer`) - Version under the parent format.
- `parserType` (`ParserType`) - `STATIC_HANDLER`, `CSV_COLUMN_CONFIG`, or
  `PDF_TEXT_TABLE_CONFIG`.
- `handlerKey` (`String`) - Internal static extractor key for built-in parser
  implementations.
- `configSchemaVersion` (`Integer`) - Parser config schema version.
- `parserConfig` (`String`) - Serialized parser configuration, such as CSV
  column mapping JSON.
- `priority` (`Integer`) - Parser selection priority.
- `enabled` (`boolean`) - Whether the parser revision can be selected.

**Business Rules:**

- Preview selects an enabled parser revision for the selected visible statement
  format.
- Preview tokens and file import records carry both the statement format ID and
  parser revision ID for provenance.

### SavedView

**Purpose:** Stores a user-owned transaction filter with optional pinned and
excluded transaction overrides.

**Key Attributes:**

- `id` (`UUID`) - Database-generated saved view identifier.
- `userId` (`String`) - User that owns the view.
- `name` (`String`) - User-facing view name.
- `criteria` (`ViewCriteria`) - Transaction filter criteria.
- `openEnded` (`boolean`) - Whether a missing upper date bound resolves to the
  current date.
- `pinnedIds` (`Set<Long>`) - Transaction IDs explicitly included.
- `excludedIds` (`Set<Long>`) - Transaction IDs explicitly excluded.
- `createdAt`, `updatedAt` (`Instant`) - Audit timestamps.

**Business Rules:**

- Saved-view criteria cannot supply an owner ID; the service injects the
  authenticated user.
- Pinning a transaction removes it from exclusions. Excluding a transaction
  removes it from pins.
- Membership semantics are documented in [Saved Views](saved-views.md).

### ViewCriteria

**Purpose:** Value object for saved-view transaction filters.

**Fields:**

- `dateFrom`, `dateTo`
- `accountIds`
- `bankNames`
- `currencyIsoCodes`
- `minAmount`, `maxAmount`
- `type`
- `searchText`

All fields are optional. Null fields are not applied as filters.

## Domain Relationships

```text
Transaction 0..1 -> 1 FileImport
FileImport -> StatementFormat by statementFormatId
FileImport -> ParserRevision by parserRevisionId
StatementFormat 1 -> * ParserRevision
SavedView 1 -> * Transaction IDs through pinnedIds and excludedIds
StatementFormat -> Transaction import flow through public ID metadata
```

## Enums

- `TransactionType` - `DEBIT`, `CREDIT`
- `FormatType` - `CSV`, `PDF`, `XLSX`
- `StatementFormatScope` - `SYSTEM`, `USER`
- `ParserType` - `STATIC_HANDLER`, `CSV_COLUMN_CONFIG`, `PDF_TEXT_TABLE_CONFIG`
- `MembershipType` - `MATCHED`, `PINNED`

## Discovery Commands

```bash
# Find all domain entities
find src/main/java/org/budgetanalyzer/transaction/domain -maxdepth 1 -name "*.java" -type f

# View repositories
find src/main/java/org/budgetanalyzer/transaction/repository -name "*.java" -type f

# View database migrations
ls src/main/resources/db/migration/
```

## References

- [Database Schema](database-schema.md)
- [API Documentation](api/README.md)
- [Statement Import System](statement-import.md)
- [Transaction Duplicate Detection](duplicate-detection.md)
- [Saved Views](saved-views.md)
