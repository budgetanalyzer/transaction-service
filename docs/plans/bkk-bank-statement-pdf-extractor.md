# Bangkok Bank Statement PDF Extractor Plan

## Goal

Add support for importing Bangkok Bank statement PDFs through a dedicated PDF
extractor using the new format key `bkk-bank-statement-pdf`.

The existing Bangkok Bank CSV statement format key, `bkk-bank-statement-csv`,
already includes the `-csv` suffix and should remain unchanged.

## Current State

- `bkk-bank-statement-csv` is seeded as a CSV format with these columns:
  - `Date`
  - `Particulars`
  - `Withdrawal`
  - `Deposit`
- Date parsing for the CSV format uses `dd/MM/yy`.
- PDF imports are handled by Spring `@Component` implementations of
  `StatementExtractor`.
- CSV imports are handled dynamically from `statement_format` rows through
  `ConfigurableCsvStatementExtractor`.
- PDF `statement_format` rows are metadata only; parsing logic lives in the
  dedicated extractor.

## Format Decisions

- New format key: `bkk-bank-statement-pdf`.
- Display name: `Bangkok Bank - Statement PDF`.
- Bank name: `Bangkok Bank`.
- Currency: `THB`.
- Keep the existing CSV format key: `bkk-bank-statement-csv`.
- Do not rename existing formats as part of this work.

## Implementation Steps

1. Read prerequisite standards before changing Java code:
   - `../service-common/docs/code-quality-standards.md`
   - `../service-common/docs/testing-patterns.md` if adding or changing tests.

2. Add a database migration:
   - Insert a PDF `statement_format` row for `bkk-bank-statement-pdf`.
   - Set `format_type` to `PDF`.
   - Set `bank_name` to `Bangkok Bank`.
   - Set `default_currency_iso_code` to `THB`.
   - Set `display_name` to `Bangkok Bank - Statement PDF`.
   - Leave CSV-specific columns null.

3. Add `BangkokBankStatementPdfExtractor`:
   - Place it in `src/main/java/org/budgetanalyzer/transaction/service/extractor/`.
   - Annotate it with `@Component`.
   - Implement `StatementExtractor`.
   - Return `bkk-bank-statement-pdf` from `getFormatKey()`.
   - Use PDFBox for text extraction, following existing PDF extractor patterns.

4. Implement `canHandle(...)`:
   - Return false for null filenames and non-`.pdf` filenames.
   - Extract text from the first one or two pages.
   - Match Bangkok Bank statement text and the expected table headers.
   - Avoid broad matching that could capture unrelated Bangkok Bank PDFs.

5. Implement transaction parsing:
   - Parse only rows under the expected columns:
     - `Date`
     - `Particulars`
     - `Withdrawal`
     - `Deposit`
   - Each PDF page repeats the column header; treat repeated headers as table
     starts or continuations, not as data.
   - Ignore all text before the first expected header.
   - Ignore footer, summary, page number, balance, and unrelated lines that do
     not match the transaction row shape.
   - Parse dates using `dd/MM/yy`.
   - Map `Withdrawal` values to `TransactionType.DEBIT`.
   - Map `Deposit` values to `TransactionType.CREDIT`.
   - Strip commas and non-amount decorations before creating `BigDecimal`
     amounts.
   - Set `bankName` to `Bangkok Bank` and `currencyIsoCode` to `THB`.
   - Use the supplied `accountId` for every extracted transaction.

6. Handle ambiguous rows:
   - If a line does not look like a transaction row, ignore it.
   - If a line looks like a transaction row but neither amount column is
     populated, reject it with `PDF_PARSING_ERROR`.
   - If both `Withdrawal` and `Deposit` are populated, reject it with
     `PDF_PARSING_ERROR`.
   - If the date or amount is malformed on an otherwise valid transaction row,
     reject it with `PDF_PARSING_ERROR`.

7. Add tests:
   - Add `BangkokBankStatementPdfExtractorTest`.
   - Add or extend a synthetic PDF fixture generator for Bangkok Bank.
   - Cover a multi-page PDF where each page repeats the expected columns.
   - Include non-table text before, between, and after transaction tables.
   - Assert repeated headers and unrelated lines are ignored.
   - Assert extracted date, description, amount, type, bank, currency, and
     account ID values.
   - Assert `canHandle(...)` accepts the Bangkok Bank sample PDF and rejects
     CSV files and unrelated PDFs.
   - Assert `extractEntities(...)` maps preview transactions to entities and
     links the provided `FileImport`.
   - Update seeded format integration tests to include `bkk-bank-statement-pdf`.

8. Update documentation:
   - Update `AGENTS.md` supported-format notes if needed.
   - Update `docs/statement-import.md` or split/rename it if PDF details no longer
     fit cleanly under the CSV-only title.
   - Update `docs/api/README.md` format-key examples if they list supported
     import formats.

## Verification

Run targeted checks first:

```bash
./gradlew test --tests "*BangkokBankStatementPdfExtractorTest"
./gradlew test --tests "*StatementFormatRepositoryIntegrationTest"
```

Then run the broader service checks:

```bash
./gradlew clean build
```

If `service-common` classes cannot be resolved, publish `service-common` to the
local Maven cache and rerun:

```bash
cd ../service-common
./gradlew clean build publishToMavenLocal
cd ../transaction-service
./gradlew clean build
```
