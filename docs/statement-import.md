# Statement Import System

## Overview

The statement import system provides configuration-driven CSV parsing and
dedicated PDF extractors for multiple bank statement formats. Banks have
different export formats with varying column headers, date formats, amount
representations, and PDF layouts. CSV formats can usually be added without code
changes; new PDF layouts require a dedicated `StatementExtractor`.

## Supported Banks

Currently configured banks:
- **Bangkok Bank** (THB) - Statement format

Registered PDF formats:
- **Capital One** (USD) - Credit monthly, credit yearly, and bank monthly statements
- **Bangkok Bank** (THB) - Statement PDFs with `Date`, `Particulars`,
  `Withdrawal`, and `Deposit` columns

## Configuration Structure

Statement formats are stored in the `statement_format` database table and
managed via the Statement Format API. Hidden `parser_revision` rows store the
deterministic parser configuration or static extractor handler selected during
preview. The public import identity is `statement_format.id`, not a format key.

During import preview, the service loads the selected `statement_format.id` and
tries every enabled parser revision for that format in priority and revision
order. Each revision produces an in-memory parser attempt: not applicable,
matched, or failed. The first matched attempt in deterministic order supplies
the preview rows, and the preview token records both the selected
`statementFormatId` and the winning `parserRevisionId`. Batch import then
persists the same provenance on `file_import`.

### Database Schema

```sql
CREATE TABLE statement_format (
    id BIGSERIAL PRIMARY KEY,
    format_type VARCHAR(10) NOT NULL,        -- CSV, PDF, XLSX
    bank_name VARCHAR(100) NOT NULL,
    default_currency_iso_code VARCHAR(3) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    scope VARCHAR(10) NOT NULL,              -- SYSTEM or USER
    owner_id VARCHAR(50),                    -- null for SYSTEM formats
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE,
    created_by VARCHAR(50),
    updated_by VARCHAR(50)
);

CREATE TABLE parser_revision (
    id BIGSERIAL PRIMARY KEY,
    statement_format_id BIGINT NOT NULL REFERENCES statement_format(id),
    revision_number INTEGER NOT NULL,
    parser_type VARCHAR(30) NOT NULL,        -- STATIC_HANDLER, CSV_COLUMN_CONFIG
    handler_key VARCHAR(100),                -- internal static extractor key
    config_schema_version INTEGER NOT NULL,
    parser_config TEXT,                      -- CSV column mapping JSON
    priority INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
```

### Statement Format API

- `GET /v1/statement-formats` - List formats visible to the caller
- `GET /v1/statement-formats/{id}` - Get a specific format by ID
- `POST /v1/statement-formats` - Create new format
- `PUT /v1/statement-formats/{id}` - Update format metadata or enablement
- `POST /v1/statement-formats/csv-wizard/analyze` - Analyze a CSV sample and
  infer a column mapping
- `POST /v1/statement-formats/csv-wizard/preview` - Validate a confirmed CSV
  mapping and return read-only parser preview rows
- `POST /v1/statement-formats/csv-wizard/save` - Save a user-scoped CSV format
  with one enabled parser revision

Disable a format through `PUT /v1/statement-formats/{id}` with
`{"enabled": false}`.

### Amount Column Patterns

The system supports two patterns for representing transaction amounts:

#### Pattern 1: Single Amount + Type Column

Used by: Capital One, Truist

| Field | Value |
|-------|-------|
| credit_header | "Transaction Amount" |
| debit_header | "Transaction Amount" |
| date_header | "Transaction Date" |
| date_format | "MM/dd/uu" |
| description_header | "Transaction Description" |
| type_header | "Transaction Type" |

**How it works:**
- Single column contains the amount (always positive)
- Separate column indicates whether it's a credit or debit
- Parser uses type column to determine sign

#### Pattern 2: Separate Credit/Debit Columns

Used by: Bangkok Bank

| Field | Value |
|-------|-------|
| credit_header | "Credit" or "เครดิต" |
| debit_header | "Debit" or "เดบิต" |
| date_header | "Date" or "วันที่" |
| date_format | "dd/MM/uuuu" |
| description_header | "Description" or "รายละเอียด" |
| type_header | null (not used) |

**How it works:**
- Two columns: one for credits, one for debits
- Only one column has a value per row (other is empty)
- Parser determines type by which column has a value

## Complete Configuration Examples

See `V7__add_statement_format.sql` and
`V18__user_scoped_statement_formats_and_parser_revisions.sql` for seeded
formats and parser revisions. Here are sample imports:

### Bangkok Bank CSV

**Sample CSV:**
```csv
Date,Particulars,Withdrawal,Deposit
15/11/24,Coffee Shop,150.00,
14/11/24,Transfer,,5000.00
```

### Bangkok Bank PDF (`bkk-bank-statement-pdf`)

The seeded PDF format uses display name `Bangkok Bank - Statement PDF`, bank
name `Bangkok Bank`, and default currency `THB`. The dedicated PDF extractor
detects statement PDFs by requiring Bangkok Bank text plus a transaction table
with date rows after the expected `Date Particulars ... Withdrawal Deposit`
header. The native statement layout may include non-transaction columns such as
`Chq.No.`, `Balance`, and `Via`.

Transaction rows are parsed only after that header. Repeated headers on later
pages continue the same table. Withdrawal amounts import as `DEBIT`, deposit
amounts import as `CREDIT`, optional trailing `Balance` column values are
ignored, dates use `dd/MM/yy`, and amounts are stored as positive THB values. A
balance-forward row such as `B/F` is ignored because it carries only a running
balance, not a transaction amount. Rows that do not match the transaction row
shape are ignored; ambiguous rows with both amount columns populated or no
populated amount column fail with `PDF_PARSING_ERROR`. CSV-specific
configuration columns remain null for this format.

### Capital One Credit Monthly PDF

The seeded PDF format uses display name `Capital One Credit - Monthly
Statement`, bank name `Capital One`, and default currency `USD`. The dedicated
PDF extractor detects monthly credit card statements from Capital One credit
card text, a statement period, and the billing-cycle marker.

The extractor first parses the original single-line table shape, for example
`Nov 20 Nov 21 ONLINE PAYMENT THANK YOU $500.00`. If that path finds no rows,
it falls back to split-column text produced by some real PDFs where PDFBox
extracts each table cell as a separate line:

```text
May 2
May 2
CREDIT-CASH BACK REWARD
- $450.68
```

Both shapes use the same Capital One monthly credit statement format ID. Static
PDF extractor routing uses an internal `parser_revision.handler_key`; clients
select the top-level statement format by ID.
Payments, credits, and negative amounts import as `CREDIT`; purchase rows import
as `DEBIT`. Foreign-currency detail lines, exchange-rate detail lines, airline
ticket detail lines, page continuations, and summary totals are ignored. If the
statement contains a transaction table but neither parser can extract rows, the
preview fails with `PDF_PARSING_ERROR` instead of returning an empty transaction
list.

## Date Format Patterns

Uses Java `DateTimeFormatter` patterns:

| Pattern | Description | Example |
|---------|-------------|---------|
| `MM/dd/uu` | Month/Day/2-digit year | 11/15/24 |
| `dd/MM/uuuu` | Day/Month/4-digit year | 15/11/2024 |
| `M/d/uu` | Month/Day/2-digit year (no leading zeros) | 1/5/24 |
| `uuuu-MM-dd` | ISO 8601 format | 2024-11-15 |

See [DateTimeFormatter documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/format/DateTimeFormatter.html) for all patterns.

## Adding a New Bank Format

### Step 1: Obtain Sample CSV

Get a real CSV export from the bank. Review:
- Column headers (exact spelling and case)
- Date format
- Amount representation (single column or separate credit/debit)
- Transaction type indicator (if present)

### Step 2: Create Format via API

Use the CSV wizard endpoints for user-created formats when you have a sample
file. The wizard infers likely columns, lets the client submit a confirmed
mapping, validates that mapping against the sample, and saves a user-scoped
format with an enabled `CSV_COLUMN_CONFIG` parser revision. The uploaded sample
is not persisted.

The legacy JSON create endpoint is still available for clients that already
know exact column names:

```bash
curl -X POST http://localhost:8082/v1/statement-formats \
  -H "Content-Type: application/json" \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: statementformats:write" \
  -d '{
    "displayName": "New Bank CSV",
    "formatType": "CSV",
    "bankName": "New Bank Name",
    "defaultCurrencyIsoCode": "USD",
    "dateHeader": "Exact Date Column Header",
    "dateFormat": "MM/dd/uu",
    "descriptionHeader": "Exact Description Column Header",
    "creditHeader": "Exact Credit Column Header",
    "debitHeader": "Exact Debit Column Header",
    "typeHeader": "Exact Type Column Header"
  }'
```

**Important:**
- New formats are user-scoped by default. Creating `scope: "SYSTEM"` requires
  `statementformats:write:any`.
- The response `id` is the value to use for preview and update requests.
- All headers must match CSV exactly (case-sensitive)
- Date format must match CSV date representation
- Use same column for both credit/debit headers if bank uses single amount column
- Omit `typeHeader` if using separate credit/debit columns

### CSV Wizard Flow

#### Analyze Sample

```bash
curl -X POST http://localhost:8082/v1/statement-formats/csv-wizard/analyze \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: statementformats:write" \
  -F "file=@sample.csv"
```

**Response:** `200 OK`
```json
{
  "headers": ["Transaction Date", "Description", "Amount", "Type"],
  "sampleRows": [
    {
      "Transaction Date": "04/12/24",
      "Description": "Coffee Shop",
      "Amount": "4.50",
      "Type": "Debit"
    }
  ],
  "inferredMapping": {
    "dateColumn": "Transaction Date",
    "dateFormat": "MM/dd/uu",
    "descriptionColumn": "Description",
    "amountMode": "SINGLE_AMOUNT_WITH_TYPE",
    "amountColumn": "Amount",
    "debitColumn": null,
    "creditColumn": null,
    "typeColumn": "Type",
    "categoryColumn": null
  },
  "confidence": 0.95,
  "columnConfidences": {
    "dateColumn": 0.95,
    "descriptionColumn": 0.95,
    "amountColumn": 0.95,
    "typeColumn": 0.95
  },
  "warnings": []
}
```

#### Preview Confirmed Mapping

This preview is a parser validation view only. It does not create a normal
import preview token, `file_import`, statement format, or transaction rows. It
also does not perform duplicate detection or expose batch import actions.

```bash
curl -X POST http://localhost:8082/v1/statement-formats/csv-wizard/preview \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: statementformats:write" \
  -F "file=@sample.csv" \
  -F 'request={
    "bankName": "Example Bank",
    "defaultCurrencyIsoCode": "USD",
    "accountId": "checking-001",
    "mapping": {
      "dateColumn": "Transaction Date",
      "dateFormat": "MM/dd/uu",
      "descriptionColumn": "Description",
      "amountMode": "SINGLE_AMOUNT_WITH_TYPE",
      "amountColumn": "Amount",
      "typeColumn": "Type"
    }
  };type=application/json'
```

**Response:** `200 OK`
```json
{
  "transactions": [
    {
      "date": "2024-04-12",
      "description": "Coffee Shop",
      "amount": 4.50,
      "type": "DEBIT",
      "category": null,
      "bankName": "Example Bank",
      "currencyIsoCode": "USD",
      "accountId": "checking-001",
      "duplicate": false,
      "duplicateReason": null
    }
  ],
  "warnings": []
}
```

#### Save Confirmed Mapping

```bash
curl -X POST http://localhost:8082/v1/statement-formats/csv-wizard/save \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: statementformats:write" \
  -F "file=@sample.csv" \
  -F 'request={
    "displayName": "Example Bank CSV",
    "bankName": "Example Bank",
    "defaultCurrencyIsoCode": "USD",
    "mapping": {
      "dateColumn": "Transaction Date",
      "dateFormat": "MM/dd/uu",
      "descriptionColumn": "Description",
      "amountMode": "SINGLE_AMOUNT_WITH_TYPE",
      "amountColumn": "Amount",
      "typeColumn": "Type"
    }
  };type=application/json'
```

**Response:** `201 Created`
```json
{
  "id": 123,
  "displayName": "Example Bank CSV",
  "formatType": "CSV",
  "bankName": "Example Bank",
  "defaultCurrencyIsoCode": "USD",
  "scope": "USER",
  "ownerId": "usr_test123",
  "enabled": true
}
```

The saved `id` can immediately be used as `statementFormatId` in
`POST /v1/transactions/preview`.

**Validation behavior:** Wizard preview and save validate required columns,
date format, amount mode, credit/debit direction, bank name, ISO currency, and
that the mapping parses at least one valid transaction row. Mapping validation
errors return `422 Unprocessable Entity` with `code:
CSV_WIZARD_VALIDATION_FAILED` and field-addressable `fieldErrors`, for example:

```json
{
  "type": "APPLICATION_ERROR",
  "message": "CSV wizard mapping validation failed.",
  "code": "CSV_WIZARD_VALIDATION_FAILED",
  "fieldErrors": [
    {
      "field": "mapping.typeColumn",
      "message": "Column is required.",
      "rejectedValue": null
    }
  ]
}
```

### Step 3: Verify Format Created

```bash
curl -H "X-User-Id: usr_test123" \
  -H "X-Permissions: statementformats:read" \
  http://localhost:8082/v1/statement-formats/123
```

No restart required - formats are loaded from database.

### Step 4: Preview Import

Use the preview endpoint with the statement format ID returned by the create or
list API. Preview parses the file, returns editable transactions, includes
advisory duplicate indicators, and reports whether the exact file bytes match a
previous import record for the current user without persisting anything. It also
returns an encrypted, time-limited `previewImportToken` for token-backed batch
import recording:

```bash
curl -X POST http://localhost:8082/v1/transactions/preview \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: transactions:read" \
  -F "file=@sample.csv" \
  -F "statementFormatId=123" \
  -F "accountId=test-account"
```

Review the returned `transactions` array. Rows with `duplicate=true` are likely
duplicates and include `duplicateReason` of `EXISTING_TRANSACTION` or
`IN_BATCH`. The preview response no longer contains a top-level `warnings`
array; exact-file reupload status is represented by `fileImport`, and
transaction duplicate status is represented on each transaction row. See
[Transaction Duplicate Detection](duplicate-detection.md) for matching rules and
file reupload behavior.

Review the returned `fileImport` object before batch import. If
`alreadyImported=true`, the exact uploaded bytes match a previous `file_import`
record for the current user and `warningCode` is `FILE_ALREADY_IMPORTED`. Keep
`previewImportToken` as opaque client state and send it back with the reviewed
batch request.
The multipart `file` part must include a non-blank filename. Preview rejects
uploads with a missing or whitespace-only original filename before parsing the
file or returning `previewImportToken`.

### Step 5: Batch Import

Submit the reviewed transactions to the batch endpoint. Omit `allowDuplicate`
or set it to `false` for normal imports. Set it to `true` only for duplicate
rows that should be intentionally imported. Include the `previewImportToken`
from the preview response so the service can record `file_import` metadata,
including `statement_format_id` and `parser_revision_id`, and link newly
created transactions to that import:

```bash
curl -X POST http://localhost:8082/v1/transactions/batch \
  -H "Content-Type: application/json" \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: transactions:write" \
  -d '{
    "previewImportToken": "v2.dGVzdGl2MTIzNDU.Kc4WwTqfh1sFD8pxVq7Hxg",
    "transactions": [
      {
        "date": "2026-04-28",
        "description": "Coffee Shop",
        "amount": 150.00,
        "type": "DEBIT",
        "category": null,
        "bankName": "New Bank Name",
        "currencyIsoCode": "USD",
        "accountId": "test-account",
        "allowDuplicate": false
      }
    ]
  }'
```

### Step 6: Validate Results

Check database for imported transactions:

```sql
SELECT * FROM transaction
WHERE account_id = 'test-account'
ORDER BY date DESC;
```

Verify:
- Correct number of transactions
- Accurate dates
- Correct amounts (positive values, with `type` indicating credit or debit)
- Proper descriptions
- Correct currency code

## API Usage

### Preview Endpoint

**POST** `/v1/transactions/preview`

**Parameters:**
- `file` (multipart file, required) - CSV or PDF file to parse; must include a
  non-blank multipart filename
- `statementFormatId` (number, required) - Statement format ID selected from
  `GET /v1/statement-formats`
- `accountId` (string, optional) - Account to associate with previewed transactions

The service accepts statement preview uploads up to `25MB` by default. Override
`TRANSACTION_IMPORT_MAX_FILE_SIZE` and `TRANSACTION_IMPORT_MAX_REQUEST_SIZE` for
larger files, and keep any gateway body-size limit aligned with those values to
avoid `413 Request Entity Too Large` responses before the service handles the
request.

**Example:**
```bash
curl -X POST http://localhost:8082/v1/transactions/preview \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: transactions:read" \
  -F "file=@statement.csv" \
  -F "statementFormatId=123" \
  -F "accountId=checking-001"
```

**Response:** `200 OK`
```json
{
  "sourceFile": "statement.csv",
  "statementFormatId": 123,
  "previewImportToken": "v2.dGVzdGl2MTIzNDU.Kc4WwTqfh1sFD8pxVq7Hxg",
  "fileImport": {
    "alreadyImported": false
  },
  "transactions": [
    {
      "date": "2026-04-28",
      "description": "Coffee Shop",
      "amount": 4.50,
      "type": "DEBIT",
      "bankName": "Bangkok Bank",
      "currencyIsoCode": "THB",
      "accountId": "checking-001",
      "duplicate": false
    }
  ]
}
```

**Missing Filename Error Response:** `422 Unprocessable Entity`
```json
{
  "type": "APPLICATION_ERROR",
  "message": "Uploaded file must include an original filename.",
  "code": "MISSING_ORIGINAL_FILENAME"
}
```

**Parsing Error Response:** `422 Unprocessable Entity`
```json
{
  "type": "APPLICATION_ERROR",
  "message": "CSV parsing error in file 'statement.csv' at line 12: Invalid date format",
  "code": "CSV_PARSING_ERROR"
}
```

### Batch Import Endpoint

**POST** `/v1/transactions/batch`

**Request Body:**
- `previewImportToken` (string, required) - Opaque token returned by preview
- `transactions` (array, required) - Reviewed transactions from preview
- `allowDuplicate` (boolean, optional per row) - Defaults to `false`

The batch endpoint is token-backed. There is no manual no-file batch import
path for file preview results: clients must submit the `previewImportToken`
returned by the preview endpoint.

**Response:** `200 OK`
```json
{
  "created": 1,
  "duplicatesSkipped": 0,
  "duplicatesImported": 0,
  "transactions": [
    {
      "id": 101,
      "ownerId": "usr_test123",
      "accountId": "checking-001",
      "bankName": "Capital One",
      "date": "2026-04-28",
      "currencyIsoCode": "USD",
      "amount": 4.50,
      "type": "DEBIT",
      "description": "Coffee Shop",
      "createdAt": "2026-04-28T18:30:00Z",
      "updatedAt": "2026-04-28T18:30:00Z"
    }
  ]
}
```

### Duplicate Detection

Preview duplicate flags are advisory. Batch import always re-checks duplicates
before persistence because stored transactions can change after preview. Use
`allowDuplicate=true` only for rows that should be intentionally imported
despite duplicate detection.

See [Transaction Duplicate Detection](duplicate-detection.md) for the
authoritative matching rules, `duplicateReason` values, file reupload tracking,
`previewImportToken` behavior, and empty-import failure semantics.

## Implementation Details

### Parser Flow

1. **File Validation** - Check file format, size limits
2. **Configuration Lookup** - Retrieve the visible statement format by ID and
   choose its enabled parser revision
3. **Header Parsing** - Read and validate CSV headers match config
4. **Row Parsing** - For each row:
   - Parse date using configured format
   - Extract amount (from single column or credit/debit columns)
   - Determine transaction type
   - Extract description
5. **Preview Response** - Return editable transactions with duplicate metadata
6. **Batch Import** - Persist reviewed transactions in a single transaction
7. **Error Handling** - Roll back on any parsing error

### Error Handling

Batch imports are transactional:
- Success: All non-skipped transactions from the request are saved
- Failure: No transactions saved, detailed error message returned

Error messages include:
- Filename where error occurred
- Line number
- Specific parsing error

### Key Classes

- `StatementFormat` - Entity representing a statement format configuration
- `ParserRevision` - Hidden parser configuration or static extractor routing
- `StatementFormatService` - CRUD operations for statement formats
- `StatementExtractorRegistry` - Registry of extractors by statement format ID
  and parser revision
- `ConfigurableCsvStatementExtractor` - Extracts transactions from CSV files
- `TransactionController.previewTransactions()` - Preview API endpoint
- `TransactionController.batchImportTransactions()` - Batch import API endpoint
- `TransactionImportService` - Business logic for imports

### Discovery Commands

```bash
# View statement format entity
cat src/main/java/org/budgetanalyzer/transaction/domain/StatementFormat.java

# View format service
cat src/main/java/org/budgetanalyzer/transaction/service/StatementFormatService.java

# View seeded formats
cat src/main/resources/db/migration/V7__add_statement_format.sql
cat src/main/resources/db/migration/V18__user_scoped_statement_formats_and_parser_revisions.sql

# Find import endpoints
grep -r "import\|preview" src/main/java/*/api/ | grep "@PostMapping"
```

## Troubleshooting

### "Statement format has no supported parser revision"

**Cause:** The selected statement format ID is not visible, disabled, or has no
enabled parser revision compatible with its file type.

**Solution:** List formats via `GET /v1/statement-formats` and verify the
selected format is enabled and has a parser revision.

### "Invalid date format at line X"

**Cause:** Date format in CSV doesn't match `dateFormat` pattern.

**Solution:**
1. Check actual date format in CSV
2. Create a corrected format or parser revision. Metadata updates use
   `PUT /v1/statement-formats/{id}`.

### "Missing required header: Amount"

**Cause:** CSV header doesn't match configured header names.

**Solution:**
1. Check exact header names in CSV (case-sensitive)
2. Create a corrected format or parser revision. Metadata updates use
   `PUT /v1/statement-formats/{id}`.

### "Duplicate transactions detected"

**Cause:** CSV contains transactions already in database.

**Solution:**
- Consider filtering CSV to only new transactions
- When using the preview-to-batch flow, set `allowDuplicate=true` only on rows
  that should be intentionally imported despite matching duplicate detection.
- Preview responses mark likely duplicates before import with `duplicate=true`
  and `duplicateReason` of `EXISTING_TRANSACTION` or `IN_BATCH`.
- See [Transaction Duplicate Detection](duplicate-detection.md) for the exact
  matching rules and batch re-check behavior.

### Empty amounts parsed as zero

**Cause:** Row has values in both credit and debit columns (should be mutually exclusive).

**Solution:** Review bank CSV export. One of the columns should be empty per row.

## Best Practices

1. **Test with real data** - Always use actual bank exports, not synthetic test files
2. **Start small** - Import small CSV files first (10-20 transactions) to validate config
3. **Check results** - Query database after import to verify accuracy
4. **Document format variations** - If bank has multiple export formats, create separate configs
5. **Currency codes** - Always use ISO 4217 codes (USD, EUR, THB, etc.)
6. **Date formats** - Match exact format from CSV, including leading zeros
7. **Header matching** - Headers are case-sensitive and must match exactly

## Future Enhancements

Potential improvements (not yet implemented):

- Column order flexibility (currently order-dependent)
- Optional column support
- Custom data transformations
- Validation rules per bank
- Async import for large files
- Import status tracking
- Partial import support (continue on row errors)
