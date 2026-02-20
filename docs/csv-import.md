# CSV Import System

## Overview

The CSV Import system provides configuration-driven parsing for multiple bank statement formats. Banks have different CSV export formats with varying column headers, date formats, and amount representations. This system eliminates the need for code changes when adding new bank formats.

## Supported Banks

Currently configured banks:
- **Capital One** (USD) - Single amount column with type indicator (CSV + PDF)
- **Bangkok Bank** (THB) - Two statement format variants with separate credit/debit columns
- **Truist** (USD) - Single amount column with type indicator

## Configuration Structure

Statement formats are stored in the `statement_format` database table and managed via the Statement Format API.

### Database Schema

```sql
CREATE TABLE statement_format (
    id BIGSERIAL PRIMARY KEY,
    format_key VARCHAR(50) NOT NULL UNIQUE,  -- e.g., "capital-one"
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
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
```

### Statement Format API

- `GET /v1/statement-formats` - List all formats
- `GET /v1/statement-formats/{formatKey}` - Get specific format
- `POST /v1/statement-formats` - Create new format
- `PUT /v1/statement-formats/{formatKey}` - Update format
- `DELETE /v1/statement-formats/{formatKey}` - Disable format (soft delete)

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

### Capital One

**Sample CSV:**
```csv
Transaction Date,Transaction Description,Transaction Type,Transaction Amount
11/15/24,Coffee Shop,Debit,4.50
11/14/24,Paycheck,Credit,2500.00
```

### Bangkok Bank (bkk-bank format)

**Sample CSV:**
```csv
Date,Description,Debit,Credit
15 Nov 2024 09:30,Coffee Shop,150.00,
14 Nov 2024 14:00,Transfer,,5000.00
```

### Bangkok Bank (bkk-bank-statement format)

**Sample CSV:**
```csv
Date,Particulars,Withdrawal,Deposit
15/11/24,Coffee Shop,150.00,
14/11/24,Transfer,,5000.00
```

### Truist

**Sample CSV:**
```csv
Transaction Date,Description,Transaction Type,Amount
11/15/2024,Coffee Shop,Debit,4.50
11/04/2024,Paycheck,Credit,2500.00
```

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

### Step 4: Test Import

Use the import endpoint with your format key:

```bash
curl -X POST http://localhost:8082/v1/transactions/import \
  -F "files=@sample.csv" \
  -F "csvFormat=new-bank-format" \
  -F "accountId=test-account"
```

### Step 5: Validate Results

Check database for imported transactions:

```sql
SELECT * FROM transaction
WHERE account_id = 'test-account'
ORDER BY transaction_date DESC;
```

Verify:
- Correct number of transactions
- Accurate dates
- Correct amounts (positive for credits, negative for debits)
- Proper descriptions
- Correct currency code

## API Usage

### Import Endpoint

**POST** `/v1/transactions/import`

**Parameters:**
- `files` (multipart file, required, multiple) - CSV files to import
- `csvFormat` (string, required) - Format key from configuration
- `accountId` (string, required) - Account to associate transactions with

**Example:**
```bash
curl -X POST http://localhost:8082/v1/transactions/import \
  -F "files=@statement1.csv" \
  -F "files=@statement2.csv" \
  -F "csvFormat=capital-one" \
  -F "accountId=checking-001"
```

**Response:** `200 OK`
```json
{
  "message": "Successfully imported 45 transactions from 2 files"
}
```

**Error Response:** `400 Bad Request`
```json
{
  "timestamp": "2024-11-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "CSV parsing error in file 'statement1.csv' at line 12: Invalid date format",
  "path": "/v1/transactions/import"
}
```

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
5. **Transaction Creation** - Create Transaction entities
6. **Batch Save** - Persist all transactions in single transaction
7. **Error Handling** - Roll back on any parsing error

### Error Handling

All imports are transactional:
- Success: All transactions from all files saved
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
- `TransactionController.importTransactions()` - API endpoint
- `TransactionImportService` - Business logic for imports

### Discovery Commands

```bash
# View statement format entity
cat src/main/java/org/budgetanalyzer/transaction/domain/StatementFormat.java

# View format service
cat src/main/java/org/budgetanalyzer/transaction/service/StatementFormatService.java

# View seeded formats
cat src/main/resources/db/migration/V7__add_statement_format.sql

# Find import endpoint
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
- Current implementation doesn't check for duplicates
- Consider filtering CSV to only new transactions
- Or implement duplicate detection (requires unique business key)

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

- Duplicate detection using transaction hash
- Column order flexibility (currently order-dependent)
- Optional column support
- Custom data transformations
- Validation rules per bank
- Async import for large files
- Import status tracking
- Partial import support (continue on row errors)
