# CSV Import System

## Overview

The CSV Import system provides configuration-driven parsing for multiple bank statement formats. Banks have different CSV export formats with varying column headers, date formats, and amount representations. This system eliminates the need for code changes when adding new bank formats.

## Supported Banks

Currently configured banks:
- **Capital One** (USD) - Single amount column with type indicator
- **Bangkok Bank** (THB) - Two statement format variants with separate credit/debit columns
- **Truist** (USD) - Single amount column with type indicator

## Configuration Structure

All CSV formats are defined in `src/main/resources/application.yml` under the `budget-analyzer.csv-config-map` property.

### Configuration Format

```yaml
budget-analyzer:
  csv-config-map:
    {format-key}:
      bank-name: "Display name of the bank"
      default-currency-iso-code: "ISO 4217 currency code"
      credit-header: "Column header for credit amounts"
      debit-header: "Column header for debit amounts"
      date-header: "Column header for transaction date"
      date-format: "Java DateTimeFormatter pattern"
      description-header: "Column header for description"
      type-header: "Column header for transaction type (optional)"
```

### Amount Column Patterns

The system supports two patterns for representing transaction amounts:

#### Pattern 1: Single Amount + Type Column

Used by: Capital One, Truist

```yaml
capital-one:
  bank-name: "Capital One"
  default-currency-iso-code: "USD"
  credit-header: "Transaction Amount"    # Same column for both
  debit-header: "Transaction Amount"     # Same column for both
  date-header: "Transaction Date"
  date-format: "MM/dd/uu"
  description-header: "Transaction Description"
  type-header: "Transaction Type"        # "Credit" or "Debit"
```

**How it works:**
- Single column contains the amount (always positive)
- Separate column indicates whether it's a credit or debit
- Parser uses type column to determine sign

#### Pattern 2: Separate Credit/Debit Columns

Used by: Bangkok Bank

```yaml
bangkok-bank-statement-1:
  bank-name: "Bangkok Bank"
  default-currency-iso-code: "THB"
  credit-header: "เครดิต"                # Thai for "Credit"
  debit-header: "เดบิต"                  # Thai for "Debit"
  date-header: "วันที่"                  # Thai for "Date"
  date-format: "dd/MM/uuuu"
  description-header: "รายละเอียด"        # Thai for "Description"
  # No type-header - credit/debit implicit from column
```

**How it works:**
- Two columns: one for credits, one for debits
- Only one column has a value per row (other is empty)
- Parser determines type by which column has a value

## Complete Configuration Examples

### Capital One

```yaml
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

**Sample CSV:**
```csv
Transaction Date,Transaction Description,Transaction Type,Transaction Amount
11/15/24,Coffee Shop,Debit,4.50
11/14/24,Paycheck,Credit,2500.00
```

### Bangkok Bank (Statement Format 1)

```yaml
bangkok-bank-statement-1:
  bank-name: "Bangkok Bank"
  default-currency-iso-code: "THB"
  credit-header: "เครดิต"
  debit-header: "เดบิต"
  date-header: "วันที่"
  date-format: "dd/MM/uuuu"
  description-header: "รายละเอียด"
```

**Sample CSV:**
```csv
วันที่,รายละเอียด,เดบิต,เครดิต
15/11/2024,ร้านกาแฟ,150.00,
14/11/2024,โอนเงิน,,5000.00
```

### Bangkok Bank (Statement Format 2)

```yaml
bangkok-bank-statement-2:
  bank-name: "Bangkok Bank"
  default-currency-iso-code: "THB"
  credit-header: "Credit"
  debit-header: "Withdrawal"
  date-header: "Date"
  date-format: "dd/MM/uuuu"
  description-header: "Description"
```

**Sample CSV:**
```csv
Date,Description,Withdrawal,Credit
15/11/2024,Coffee Shop,150.00,
14/11/2024,Transfer,,5000.00
```

### Truist

```yaml
truist:
  bank-name: "Truist"
  default-currency-iso-code: "USD"
  credit-header: "Amount"
  debit-header: "Amount"
  date-header: "Date"
  date-format: "M/d/uu"
  description-header: "Description"
  type-header: "Type"
```

**Sample CSV:**
```csv
Date,Description,Type,Amount
11/15/24,Coffee Shop,Debit,4.50
11/4/24,Paycheck,Credit,2500.00
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

### Step 2: Add Configuration

Edit `src/main/resources/application.yml`:

```yaml
budget-analyzer:
  csv-config-map:
    # Existing configs...

    new-bank-format:
      bank-name: "New Bank Name"
      default-currency-iso-code: "USD"  # or appropriate currency
      credit-header: "Exact Credit Column Header"
      debit-header: "Exact Debit Column Header"
      date-header: "Exact Date Column Header"
      date-format: "MM/dd/uu"  # Match actual format
      description-header: "Exact Description Column Header"
      type-header: "Exact Type Column Header"  # Optional - omit if using separate columns
```

**Important:**
- Format key (`new-bank-format`) must be unique and URL-safe
- All headers must match CSV exactly (case-sensitive)
- Date format must match CSV date representation
- Use same column for both credit/debit headers if bank uses single amount column

### Step 3: Restart Service

```bash
./gradlew bootRun
```

Configuration changes require restart (no code changes needed).

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

- `CsvTransactionMapper` - Converts CSV rows to Transaction entities
- `TransactionController.importTransactions()` - API endpoint
- `TransactionService.importTransactions()` - Business logic
- CSV config loaded from `application.yml` via `@ConfigurationProperties`

### Discovery Commands

```bash
# View CSV mapper implementation
cat src/main/java/org/budgetanalyzer/transaction/service/impl/CsvTransactionMapper.java

# Find import endpoint
grep -r "import" src/main/java/*/api/ | grep "@PostMapping"

# View current configurations
cat src/main/resources/application.yml | grep -A 10 "csv-config-map"
```

## Troubleshooting

### "Unknown CSV format: xyz"

**Cause:** Format key not found in configuration.

**Solution:** Check format key in `application.yml`. Must match exactly.

### "Invalid date format at line X"

**Cause:** Date format in CSV doesn't match `date-format` pattern.

**Solution:**
1. Check actual date format in CSV
2. Update `date-format` in configuration
3. Restart service

### "Missing required header: Amount"

**Cause:** CSV header doesn't match configured header names.

**Solution:**
1. Check exact header names in CSV (case-sensitive)
2. Update configuration to match
3. Restart service

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
