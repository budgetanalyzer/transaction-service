# Statement Import System

## Overview

The statement import system provides configuration-driven CSV parsing,
dedicated PDF extractors for multiple bank statement formats, and internal
configuration primitives for text-based PDF table parsing. Banks have different
export formats with varying column headers, date formats, amount
representations, and PDF layouts. CSV formats can usually be added without code
changes. User-created generic PDF formats can be validated with the PDF wizard
preview endpoint and routed through normal import when saved with a
`PDF_TEXT_TABLE_CONFIG` parser revision.

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
preview. The public import identity is always `statement_format.id`.

During grouped import preview, the service loads the selected
`statement_format.id` once, then processes the ordered uploads with that shared
format and optional account. For each file it tries every enabled parser
revision in priority and revision order. Each revision produces an in-memory
parser attempt: not applicable, matched, or failed. The first matched attempt
in deterministic order supplies that file's preview rows, and its distinct
preview token records both the selected `statementFormatId` and the winning
`parserRevisionId`. Different files can select different revisions of the same
public format. Batch import then persists the token's provenance on
`file_import`.

Parser attempts are single-pass. A configurable CSV revision parses the CSV
once with the shared CSV parser, validates mapped headers from that parsed
result, and maps those same rows. Date-time CSV patterns can accept date-only
rows only when the configured pattern contains a removable `HH`, `HH:mm`, or
`HH:mm:ss` time component. A configurable text-PDF revision extracts one
`PdfTextDocument`, selects matching table candidates from that document,
enforces the configured minimum row count during candidate selection, and
parses those candidates. Dedicated PDF handlers load their parsing
representation once, perform bank and statement signature checks on it, and
then parse it. Dynamic configurable extractors are constructed directly from
the parser revision being attempted; the registry keeps only an immutable map
of static handler keys.

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
    parser_type VARCHAR(30) NOT NULL,        -- STATIC_HANDLER, CSV_COLUMN_CONFIG, PDF_TEXT_TABLE_CONFIG
    handler_key VARCHAR(100),                -- internal static extractor key; null for config parsers
    config_schema_version INTEGER NOT NULL,
    parser_config TEXT,                      -- opaque parser config JSON for config parsers
    priority INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE statement_format_user_preference (
    id BIGSERIAL PRIMARY KEY,
    statement_format_id BIGINT NOT NULL REFERENCES statement_format(id),
    user_id VARCHAR(50) NOT NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_statement_format_user_preference
        UNIQUE (statement_format_id, user_id)
);

CREATE INDEX idx_statement_format_user_preference_user_hidden
    ON statement_format_user_preference(user_id, hidden);
```

### Statement Format API

- `GET /v1/statement-formats` - List formats visible to the caller, excluding
  formats hidden by the current user
- `GET /v1/statement-formats?includeHidden=true` - Include the current user's
  hidden formats for management screens; list responses include a `hidden`
  field
- `GET /v1/statement-formats/{id}` - Get a specific format by ID
- `POST /v1/statement-formats` - Create new format
- `PUT /v1/statement-formats/{id}` - Update format metadata or enablement
- `POST /v1/statement-formats/{id}/hide` - Hide a format from the current
  user's normal import selection lists
- `POST /v1/statement-formats/{id}/unhide` - Restore a hidden format to the
  current user's normal import selection lists
- `POST /v1/statement-formats/csv-wizard/analyze` - Analyze a CSV sample and
  infer a column mapping
- `POST /v1/statement-formats/csv-wizard/preview` - Validate a confirmed CSV
  mapping and return read-only parser preview rows
- `POST /v1/statement-formats/csv-wizard/save` - Save a user-scoped CSV format
  with one enabled parser revision
- `POST /v1/statement-formats/pdf-wizard/analyze` - Analyze a text-PDF sample
  and return ranked transaction-table candidates
- `POST /v1/statement-formats/pdf-wizard/preview` - Validate a confirmed
  text-PDF table mapping and return read-only parser preview rows
- `POST /v1/statement-formats/pdf-wizard/save` - Save a user-scoped PDF format
  with one enabled `PDF_TEXT_TABLE_CONFIG` parser revision

Disable a format through `PUT /v1/statement-formats/{id}` with
`{"enabled": false}`.

Hiding is a per-user preference stored in
`statement_format_user_preference.hidden`. It is idempotent, does not disable
the format, and does not affect other users. Hidden formats remain
operationally available through direct ID lookups and import previews when the
caller has access and the format is enabled. Hidden formats remain separate from
disabled formats: disabled formats are operationally unavailable for new
previews, while hidden formats are only omitted from normal dropdown-style
selection lists.

Hide and unhide require `statementformats:write` or `statementformats:write:any`.
The target format must be visible to the current user; users cannot create
preferences for another user's private custom formats.

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
| `d MMM uuuu` | Day short-month 4-digit year | 5 Dec 2025 |
| `d MMM uuuu HH:mm` | Day short-month 4-digit year with 24-hour time | 31 Dec 2025 10:37 |

See [DateTimeFormatter documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/format/DateTimeFormatter.html) for all patterns.
When a CSV format is configured with a supported date-time pattern such as
`d MMM uuuu HH:mm`, rows from the same bank export may omit the time; the parser
falls back to the matching date-only pattern for those rows.

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

The JSON create endpoint is available for clients that already know exact
column names:

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
- Date format must use syntactically valid Java date-time pattern syntax and
  match the CSV date representation
- Use same column for both credit/debit headers if bank uses single amount column
- Omit `typeHeader` if using separate credit/debit columns
- The JSON create endpoint only creates CSV formats. Built-in PDF formats need
  parser revisions with internal handler keys and are seeded by migrations.
- Invalid `dateFormat` pattern syntax is rejected with a `dateFormat` field
  error before creating either the statement format or its initial parser
  revision.

### Generic Text-PDF Parser Foundation

`PDF_TEXT_TABLE_CONFIG` powers deterministic user-created PDF table formats. Its
parser configuration is stored as opaque text in
`parser_revision.parser_config`, with queryable metadata kept in normal
`parser_revision` columns. Schema version 1 supports text-based PDFs with
transaction-like tables, a date column, a description column, and either a
signed amount column or separate debit and credit columns. Multi-page
statements are supported when continuation tables repeat the configured
headers; matching table candidates are parsed in page and line order. A
PDFBox-based text extraction component rejects scanned or OCR-dependent PDFs
when embedded text is unavailable. The extractor applies `minimumRows` to the
selected candidate row count before row parsing; row-width and blank-value
checks still run while parsing because extracted PDF rows are external,
layout-derived input. The PDF wizard analysis endpoint scores text table
candidates by header detection, repeated headers, row continuity, row count, date-like
columns, description-like columns, signed amount columns, debit/credit column
pairs, and optional type columns.

The same deterministic extractor is used by the PDF wizard preview endpoint
and by normal import revision selection for saved `PDF_TEXT_TABLE_CONFIG`
parser revisions. A matched normal import records the winning parser revision
ID in the preview token and later on `file_import`. Static PDF handlers
continue to use `parser_type = STATIC_HANDLER` and internal `handler_key`
values.

PDF wizard mappings default to `minimumRows = 1` when the client omits the
field, because valid statements can contain a single transaction row. Yearless
numeric dates such as `05/18` are supported when `yearSource` is
`STATEMENT_PERIOD` and the statement text contains a four-digit year.

PDF wizard uploads are setup samples only. The service does not persist the
sample file or extracted text during analysis, mapping preview, or save. The
preview endpoint returns short parser diagnostics in the response; failed
normal import revision attempts remain transient and are not stored.

### PDF Wizard Analysis

The PDF wizard analysis endpoint is a setup helper only. It extracts text from
a sample PDF, returns ranked transaction-table candidates and inferred mappings,
and never persists the uploaded file or creates import state.

```bash
curl -X POST http://localhost:8082/v1/statement-formats/pdf-wizard/analyze \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: statementformats:write" \
  -F "file=@sample.pdf"
```

**Response:** `200 OK`
```json
{
  "candidates": [
    {
      "candidateId": "p1-l12-42",
      "pageNumber": 1,
      "startLineNumber": 12,
      "endLineNumber": 42,
      "rowCount": 30,
      "repeatedHeaderCount": 1,
      "headers": ["Date", "Description", "Amount"],
      "sampleRows": [
        ["Jan 1", "Coffee Shop", "$4.50"],
        ["Jan 2", "Payment", "-$100.00"]
      ],
      "inferredMapping": {
        "dateHeader": "Date",
        "dateFormat": "MMM d",
        "descriptionHeader": "Description",
        "amountMode": "SIGNED_AMOUNT",
        "amountHeader": "Amount",
        "debitHeader": null,
        "creditHeader": null,
        "typeHeader": null,
        "negativeMeans": "CREDIT"
      },
      "confidence": 0.91,
      "columnConfidences": {
        "dateHeader": 0.95,
        "descriptionHeader": 0.95,
        "amountHeader": 0.95
      },
      "rejectionReasons": []
    }
  ],
  "confidence": 0.91,
  "rejectionReasons": []
}
```

Unsupported or low-confidence PDFs return `200 OK` with empty or low-confidence
candidates plus user-facing `rejectionReasons`, for example scanned-PDF
rejection when the file has too little extractable text. Malformed PDF bytes or
other text extraction failures are also represented as analysis rejection
reasons. The client should show these reasons in the wizard instead of treating
the response as an import preview.

### PDF Wizard Preview

This preview is a parser validation view only. It does not create a normal
import preview token, `file_import`, statement format, or transaction rows. It
also does not perform duplicate detection or expose batch import actions.

```bash
curl -X POST http://localhost:8082/v1/statement-formats/pdf-wizard/preview \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: statementformats:write" \
  -F "file=@sample.pdf" \
  -F 'request={
    "bankName": "Example Bank",
    "defaultCurrencyIsoCode": "USD",
    "accountId": "checking-001",
    "headerMustContain": ["Date", "Description", "Amount"],
    "minimumRows": 1,
    "yearSource": "EXPLICIT_DATE",
    "mapping": {
      "dateHeader": "Date",
      "dateFormat": "MM/dd/uuuu",
      "descriptionHeader": "Description",
      "amountMode": "SIGNED_AMOUNT",
      "amountHeader": "Amount",
      "negativeMeans": "CREDIT"
    }
  };type=application/json'
```

**Response:** `200 OK`
```json
{
  "transactions": [
    {
      "date": "2025-01-02",
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
  "diagnostics": [
    "Matched a text-PDF table using 3 configured header token(s)."
  ]
}
```

Preview uploads must include a `.pdf` filename. For signed amount columns,
`negativeMeans` defines the transaction direction for negative values; positive
values are imported as the opposite direction. As an alternative, a configured
`typeHeader` can provide row direction values such as `Debit`, `Credit`, `Dr`,
or `Cr`, in which case `negativeMeans` is only used as a fallback when present.
For separate debit and credit columns, exactly one of those columns must be
populated per row. Supported month-name date formats include abbreviated and
full month names, with or without a comma before a four-digit year, for example
`MMM d`, `MMM d, uuuu`, `MMMM d`, and `MMMM d, uuuu`. Yearless date formats
require `yearSource: STATEMENT_PERIOD` and a four-digit year elsewhere in the
extracted PDF text. Mapping validation errors return `422 Unprocessable
Entity` with `code: PDF_WIZARD_VALIDATION_FAILED` and field-addressable
`fieldErrors`.

### PDF Wizard Save

The save endpoint validates the confirmed mapping against the uploaded sample
PDF before persisting anything. On success it creates a user-scoped
`statement_format` with `formatType = PDF` and exactly one enabled
`PDF_TEXT_TABLE_CONFIG` parser revision. The saved `id` can immediately be used
as `statementFormatId` in `POST /v1/transactions/preview`.

```bash
curl -X POST http://localhost:8082/v1/statement-formats/pdf-wizard/save \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: statementformats:write" \
  -F "file=@sample.pdf" \
  -F 'request={
    "displayName": "Example Bank PDF",
    "bankName": "Example Bank",
    "defaultCurrencyIsoCode": "USD",
    "headerMustContain": ["Date", "Description", "Amount"],
    "minimumRows": 1,
    "yearSource": "EXPLICIT_DATE",
    "mapping": {
      "dateHeader": "Date",
      "dateFormat": "MM/dd/uuuu",
      "descriptionHeader": "Description",
      "amountMode": "SIGNED_AMOUNT",
      "amountHeader": "Amount",
      "negativeMeans": "CREDIT"
    }
  };type=application/json'
```

**Response:** `201 Created`
```json
{
  "id": 124,
  "displayName": "Example Bank PDF",
  "formatType": "PDF",
  "bankName": "Example Bank",
  "defaultCurrencyIsoCode": "USD",
  "scope": "USER",
  "ownerId": "usr_test123",
  "enabled": true
}
```

Save uses the same validation rules as PDF wizard preview: bank name and ISO
currency are required, the uploaded sample must have a `.pdf` filename, the
date format must be supported, signed amount columns must declare either
`negativeMeans` or a usable `typeHeader`, separate debit and credit columns
must be unambiguous, and the sample must parse at least `minimumRows`
transactions. Validation errors return `422 Unprocessable Entity` with `code:
PDF_WIZARD_VALIDATION_FAILED`.

The saved format participates in the same revision-selection behavior as
seeded PDF formats. Clients do not select parser revisions directly; they keep
using the returned top-level statement format `id`.

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
list API. Preview parses one or more ordered files using the shared format and
optional account. It returns one result per source with editable transactions,
advisory duplicate indicators, exact-file reupload status, and a distinct
encrypted, time-limited `previewImportToken`. No preview data is persisted:

```bash
curl -X POST http://localhost:8082/v1/transactions/preview \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: transactions:read" \
  -F "files=@january.csv" \
  -F "files=@february.csv" \
  -F "statementFormatId=123" \
  -F "accountId=test-account"
```

Review each item in the returned `files` array. Rows with `duplicate=true` are
likely duplicates and include `duplicateReason` of `EXISTING_TRANSACTION` or
`IN_BATCH`. `IN_BATCH` means a match in a completed earlier source file;
repeated rows within the same source are not compared. Persisted matches take
precedence. The preview response has no top-level `warnings` array; exact-file
reupload status is represented by each file's `fileImport`, and transaction
duplicate status is represented on each transaction row. See
[Transaction Duplicate Detection](duplicate-detection.md) for matching rules and
file reupload behavior.

Review each returned `fileImport` object before batch import. If
`alreadyImported=true`, the exact uploaded bytes match a previous `file_import`
record for the current user and `warningCode` is `FILE_ALREADY_IMPORTED`. Keep
each `previewImportToken` as opaque client state and keep it nested with that
source's reviewed transactions for the grouped batch request.

Every multipart `files` part must include a non-blank filename. Preview stops
at the first failed source and returns one standard filename-bearing error with
no partial preview body. A missing or blank filename is identified by its
ordered part index.

### Step 5: Batch Import

Submit the reviewed transactions to the batch endpoint. Omit `allowDuplicate`
or set it to `false` for normal imports. Set it to `true` only for duplicate
rows that should be intentionally imported. Submit every accepted preview file
as one ordered `files` item containing its `previewImportToken` and reviewed
transactions. All tokens must share the same statement format and account, but
may identify different parser revisions. The service verifies all tokens before
persistence and links each source's created transactions to that source's
separate `file_import` metadata:

```bash
curl -X POST http://localhost:8082/v1/transactions/batch \
  -H "Content-Type: application/json" \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: transactions:write" \
  -d '{
    "files": [
      {
        "previewImportToken": "v2.january-token",
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
      },
      {
        "previewImportToken": "v2.february-token",
        "transactions": []
      }
    ]
  }'
```

This mixed request is valid: the January group can create transactions, while
the empty February group keeps its ordered zero-count response entry and
creates no `file_import` provenance. An empty group does not succeed in
isolation because the complete batch must create at least one transaction.

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
- `files` (multipart files, required and non-empty) - Repeat this part for each
  ordered CSV or PDF source; every part must include a non-blank filename
- `statementFormatId` (number, required) - Statement format ID selected from
  `GET /v1/statement-formats`
- `accountId` (string, optional) - Account to associate with previewed
  transactions; not used for duplicate detection

The service accepts each statement file part up to `25MB` by default, while the
full multipart request also defaults to `25MB`. Override
`TRANSACTION_IMPORT_MAX_FILE_SIZE` for the per-part limit and
`TRANSACTION_IMPORT_MAX_REQUEST_SIZE` for the combined files and form fields.
Keep any gateway body-size limit aligned with the intended combined request to
avoid `413 Request Entity Too Large` responses before the service handles it.

**Example:**
```bash
curl -X POST http://localhost:8082/v1/transactions/preview \
  -H "X-User-Id: usr_test123" \
  -H "X-Permissions: transactions:read" \
  -F "files=@january.csv" \
  -F "files=@february.csv" \
  -F "statementFormatId=123" \
  -F "accountId=checking-001"
```

**Response:** `200 OK`
```json
{
  "files": [
    {
      "sourceFile": "january.csv",
      "statementFormatId": 123,
      "previewImportToken": "v2.january-token",
      "fileImport": {
        "alreadyImported": false
      },
      "transactions": [
        {
          "date": "2026-01-28",
          "description": "Coffee Shop",
          "amount": 4.50,
          "type": "DEBIT",
          "bankName": "Bangkok Bank",
          "currencyIsoCode": "THB",
          "accountId": "checking-001",
          "duplicate": false
        }
      ]
    },
    {
      "sourceFile": "february.csv",
      "statementFormatId": 123,
      "previewImportToken": "v2.february-token",
      "fileImport": {
        "alreadyImported": false
      },
      "transactions": [
        {
          "date": "2026-01-28",
          "description": "Coffee Shop",
          "amount": 4.50,
          "type": "DEBIT",
          "bankName": "Bangkok Bank",
          "currencyIsoCode": "THB",
          "accountId": "checking-001",
          "duplicate": true,
          "duplicateReason": "IN_BATCH"
        }
      ]
    }
  ]
}
```

**Missing Filename Error Response:** `422 Unprocessable Entity`
```json
{
  "type": "APPLICATION_ERROR",
  "message": "Uploaded file part at index 0 must include an original filename.",
  "code": "MISSING_ORIGINAL_FILENAME"
}
```

**Parsing Error Response:** `422 Unprocessable Entity`
```json
{
  "type": "APPLICATION_ERROR",
  "message": "Failed to preview file 'february.csv': Invalid date format at line 12",
  "code": "CSV_PARSING_ERROR"
}
```

### Batch Import Endpoint

**POST** `/v1/transactions/batch`

**Request Body:**
- `files` (array, required and non-empty) - Ordered source file groups
- `files[]` elements are required and must not be `null`
- `files[].previewImportToken` (string, required) - Opaque token returned for
  that preview file
- `files[].transactions` (array, required, may be empty) - Reviewed rows from
  that source
- `files[].transactions[]` elements are required and must not be `null`
- `files[].transactions[].allowDuplicate` (boolean, optional) - Defaults to
  `false`

The batch endpoint is token-backed. There is no manual no-file batch import
path for file preview results. Every token is owner-verified before the service
transaction begins. Mixed statement format IDs or account IDs are rejected;
the response is `422 Unprocessable Entity` with type `APPLICATION_ERROR` and
code `BATCH_IMPORT_SOURCE_MISMATCH`. Different parser revision IDs are
accepted. Request-shape and business validation paths retain both indexes, for
example `files[1].transactions[4].date`; business validation messages also
identify the verified source filename. An empty source group retains its
verified source and ordered zero-count response position, but creates no
provenance when another group creates a transaction. If the aggregate request
creates no rows, whether because every group is empty or no reviewed row
survives duplicate filtering, the response is `422 Unprocessable Entity` with
type `APPLICATION_ERROR` and code `BATCH_IMPORT_NO_TRANSACTIONS_CREATED`.

**Response:** `200 OK`
```json
{
  "created": 2,
  "duplicatesSkipped": 1,
  "duplicatesImported": 0,
  "files": [
    {
      "sourceFile": "january.csv",
      "created": 1,
      "duplicatesSkipped": 0,
      "duplicatesImported": 0,
      "transactions": [
        {
          "id": 101,
          "ownerId": "usr_test123",
          "accountId": "test-account",
          "bankName": "New Bank Name",
          "date": "2026-04-28",
          "currencyIsoCode": "USD",
          "amount": 150.00,
          "type": "DEBIT",
          "description": "Coffee Shop",
          "createdAt": "2026-04-28T18:30:00Z",
          "updatedAt": "2026-04-28T18:30:00Z"
        }
      ]
    },
    {
      "sourceFile": "february.csv",
      "created": 1,
      "duplicatesSkipped": 1,
      "duplicatesImported": 0,
      "transactions": [
        {
          "id": 102,
          "ownerId": "usr_test123",
          "accountId": "test-account",
          "bankName": "New Bank Name",
          "date": "2026-05-02",
          "currencyIsoCode": "USD",
          "amount": 42.30,
          "type": "DEBIT",
          "description": "Grocery Store",
          "createdAt": "2026-05-02T18:30:00Z",
          "updatedAt": "2026-05-02T18:30:00Z"
        }
      ]
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

1. **Request Validation** - Require a non-empty ordered `files` collection and
   enforce per-part plus combined multipart size limits
2. **Configuration Lookup** - Retrieve the shared visible statement format by
   ID once
3. **Ordered File Processing** - Read, hash, check exact-import history, and
   load enabled parser revisions for each source in multipart order
4. **Single-Pass Parser Attempts** - Try each revision against the current
   upload: extension or signature mismatches are not applicable; a matched
   parser with malformed content or invalid persisted config is failed; a
   parser with nonempty valid rows is matched
5. **Selected Row Parsing** - For each winning matched attempt:
   - Parse date using configured format
   - Extract amount (from single column or credit/debit columns)
   - Determine transaction type
   - Extract description
6. **Grouped Duplicate Detection** - Load persisted candidates once, leave
   same-file repeats unmarked, and compare later files with completed earlier
   files
7. **Preview Response** - Return ordered per-file transactions, import status,
   and distinct tokens only after every source succeeds
8. **Batch Import** - Verify all source tokens, then validate, duplicate-check,
   resolve per-file provenance, and persist the complete ordered group in one
   transaction
9. **Error Handling** - Stop at the first file failure and return no partial
   preview body

### Error Handling

Batch imports are transactional:
- Success: All non-skipped transactions from every source group are saved and
  linked to their own file provenance; a zero-created source creates no new
  `file_import`
- Failure: No transactions or newly attempted `file_import` rows are saved,
  including writes attempted for sources before the source that failed; a
  detailed error response is returned

Preview parsing error messages include the failing filename (or ordered part
index when the filename is missing) and retain the parser's machine-readable
error code. Safe line or column details may also be included; file contents,
hashes, and stack details are never returned.

File-read failures use a safe, generic filename-bearing client message. The
service retains the original read or parser failure in its internal exception
cause chain for diagnostics without returning those internal details.

Batch source identity mismatches return `422 Unprocessable Entity`, type
`APPLICATION_ERROR`, and code `BATCH_IMPORT_SOURCE_MISMATCH`. Different parser
revision IDs remain valid when the statement format and account match. A batch
that creates no transactions in aggregate returns the same status and type
with code `BATCH_IMPORT_NO_TRANSACTIONS_CREATED`.

### Key Classes

- `StatementFormat` - Entity representing a statement format configuration
- `ParserRevision` - Hidden parser configuration or static extractor routing
- `StatementFormatService` - CRUD operations for statement formats
- `StatementExtractorRegistry` - Attempts enabled parser revisions in
  deterministic order and constructs configurable extractors per attempt
- `ConfigurableCsvStatementExtractor` - Attempts CSV parser revisions with one
  shared-parser pass
- `ConfigurablePdfTextTableStatementExtractor` - Attempts saved text-PDF table
  parser revisions with one PDF text extraction
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

### "`dateFormat` field error when creating a statement format"

**Cause:** The submitted `dateFormat` is not valid Java date-time pattern
syntax.

**Solution:** Use a syntactically valid Java date-time pattern that matches the
CSV values, such as `MM/dd/uu` or `uuuu-MM-dd`. The create request is rejected
without creating a statement format or parser revision.

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
