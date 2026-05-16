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

Statement formats are stored in the `statement_format` database table and managed via the Statement Format API.

### Database Schema

```sql
CREATE TABLE statement_format (
    id BIGSERIAL PRIMARY KEY,
    format_key VARCHAR(50) NOT NULL UNIQUE,  -- e.g., "bkk-bank-statement-csv"
    format_type VARCHAR(10) NOT NULL,        -- CSV, PDF, XLSX
    bank_name VARCHAR(100) NOT NULL,
    default_currency_iso_code VARCHAR(3) NOT NULL,
    -- CSV-specific fields (null for PDF/XLSX)
    date_header VARCHAR(50),
    date_format VARCHAR(50),
    description_header VARCHAR(50),
    credit_header VARCHAR(50),
    debit_header VARCHAR(50),
    type_header VARCHAR(50),
    category_header VARCHAR(50),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE,
    created_by VARCHAR(50),
    updated_by VARCHAR(50)
);
```

### Statement Format API

- `GET /v1/statement-formats` - List all formats
- `GET /v1/statement-formats/{formatKey}` - Get specific format
- `POST /v1/statement-formats` - Create new format
- `PUT /v1/statement-formats/{formatKey}` - Update format

Disable a format through `PUT /v1/statement-formats/{formatKey}` with `{"enabled": false}`.

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

See `V7__add_statement_format.sql` for all seeded formats. Here are sample CSVs:

### Bangkok Bank CSV (`bkk-bank-statement-csv`)

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

Use the Statement Format API to create the new format:

```bash
curl -X POST http://localhost:8082/v1/statement-formats \
  -H "Content-Type: application/json" \
  -d '{
    "formatKey": "new-bank-format",
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
- `formatKey` must be unique and URL-safe
- All headers must match CSV exactly (case-sensitive)
- Date format must match CSV date representation
- Use same column for both credit/debit headers if bank uses single amount column
- Omit `typeHeader` if using separate credit/debit columns

### Step 3: Verify Format Created

```bash
curl http://localhost:8082/v1/statement-formats/new-bank-format
```

No restart required - formats are loaded from database.

### Step 4: Preview Import

Use the preview endpoint with your format key. Preview parses the file, returns
editable transactions, includes advisory duplicate indicators, and reports
whether the exact file bytes match a previous import record for the current user
without persisting anything. It also returns an encrypted, time-limited
`previewImportToken` for token-backed batch import recording:

```bash
curl -X POST http://localhost:8082/v1/transactions/preview \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: transactions:read" \
  -F "file=@sample.csv" \
  -F "format=new-bank-format" \
  -F "accountId=test-account"
```

Review the returned `transactions` array. Rows with `duplicate=true` are likely
duplicates and include `duplicateReason` of `EXISTING_TRANSACTION` or
`IN_BATCH`. The preview response no longer contains a top-level `warnings`
array; exact-file reupload status is represented by `fileImport`, and
transaction duplicate status is represented on each transaction row.

Review the returned `fileImport` object before batch import. If
`alreadyImported=true`, the exact uploaded bytes match a previous `file_import`
record for the current user and `warningCode` is `FILE_ALREADY_IMPORTED`.
Keep `previewImportToken` as opaque client state. The token identifies the
uploaded source file without exposing the raw content hash or client-decodable
payload fields and must be sent back with the reviewed batch request.
The multipart `file` part must include a non-blank filename. Preview rejects
uploads with a missing or whitespace-only original filename before parsing the
file or returning `previewImportToken`.

### Step 5: Batch Import

Submit the reviewed transactions to the batch endpoint. Omit `allowDuplicate`
or set it to `false` for normal imports. Set it to `true` only for duplicate
rows that should be intentionally imported. Include the `previewImportToken`
from the preview response so the service can record `file_import` metadata and
link newly created transactions to that import:

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
- `format` (string, required) - Format key from configuration
- `accountId` (string, optional) - Account to associate with previewed transactions

**Example:**
```bash
curl -X POST http://localhost:8082/v1/transactions/preview \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: transactions:read" \
  -F "file=@statement.csv" \
  -F "format=bkk-bank-statement-csv" \
  -F "accountId=checking-001"
```

**Response:** `200 OK`
```json
{
  "sourceFile": "statement.csv",
  "detectedFormat": "bkk-bank-statement-csv",
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
before persistence because stored transactions can change after preview.

Preview duplicate marking and batch duplicate filtering are scoped to the
authenticated owner. Both first match strict financial identity fields:
`accountId`, `bankName`, `date`, `amount`, `type`, and `currencyIsoCode`. Empty
`accountId` values are equivalent to `null`, and amounts are compared at scale
2. Candidate descriptions are then matched with normalized exact or
conservative fuzzy comparison so layout, punctuation, whitespace, and minor
rendering differences do not hide likely duplicates. Fuzzy comparison is
blocked when either description contains numeric reference tokens unless the
ordered token lists match exactly.

Duplicate reasons:
- `EXISTING_TRANSACTION` - The preview row matches an active transaction
  already stored for the owner.
- `IN_BATCH` - The row duplicates an earlier row in the same preview payload.

File reupload status is separate from transaction duplicate detection. Preview
sets `fileImport.alreadyImported=true` only when the exact uploaded bytes match a
previous `file_import` record for the authenticated user. The response includes
`warningCode=FILE_ALREADY_IMPORTED` plus previous import metadata
(`originalFilename`, `importedAt`, `format`, `accountId`, and
`transactionCount`) and never exposes the raw content hash. The encrypted
`previewImportToken` is returned on every successful preview and expires based
on transaction service configuration. Batch import requires the token and
verifies it before service-layer validation, duplicate checks, or persistence.
Missing, invalid, expired, or wrong-owner tokens fail the request. If all
submitted rows are skipped as transaction duplicates, the request fails with
`BATCH_IMPORT_NO_TRANSACTIONS_CREATED` and no `file_import` row is recorded.
When rows are created, each token-backed batch transaction is linked to file
import metadata. If the exact file was already recorded for the user, created
rows link to the existing `file_import` row instead of creating a duplicate file
import record.

## Implementation Details

### Parser Flow

1. **File Validation** - Check file format, size limits
2. **Configuration Lookup** - Retrieve CSV format config by key
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
- `StatementFormatService` - CRUD operations for statement formats
- `StatementExtractorRegistry` - Registry of extractors by format key
- `CsvStatementExtractor` - Extracts transactions from CSV files
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

# Find import endpoints
grep -r "import\|preview" src/main/java/*/api/ | grep "@PostMapping"
```

## Troubleshooting

### "Unknown CSV format: xyz"

**Cause:** Format key not found in database.

**Solution:** List formats via `GET /v1/statement-formats` and verify the format key exists.

### "Invalid date format at line X"

**Cause:** Date format in CSV doesn't match `dateFormat` pattern.

**Solution:**
1. Check actual date format in CSV
2. Update format via `PUT /v1/statement-formats/{formatKey}`

### "Missing required header: Amount"

**Cause:** CSV header doesn't match configured header names.

**Solution:**
1. Check exact header names in CSV (case-sensitive)
2. Update format via `PUT /v1/statement-formats/{formatKey}`

### "Duplicate transactions detected"

**Cause:** CSV contains transactions already in database.

**Solution:**
- Consider filtering CSV to only new transactions
- When using the preview-to-batch flow, set `allowDuplicate=true` only on rows
  that should be intentionally imported despite matching duplicate detection.
- Preview responses mark likely duplicates before import with `duplicate=true`
  and `duplicateReason` of `EXISTING_TRANSACTION` or `IN_BATCH`.
- Preview duplicate marking uses account ID, bank name, date, amount, type, and
  currency to find candidates, then applies normalized exact or conservative
  fuzzy description matching. Numeric reference tokens must match exactly for
  fuzzy matches. Batch import re-checks duplicates with the same rule before
  persistence. Empty account IDs are treated the same as missing account IDs.

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
