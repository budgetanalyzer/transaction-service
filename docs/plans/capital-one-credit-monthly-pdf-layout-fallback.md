# Capital One Credit Monthly PDF Layout Fallback Plan

## Implementation Status

- Phase 1: Completed.
- Phase 2: Completed.
- Phase 3: Completed.
- Phase 4: Completed.
- Phase 5: Completed.
- Phase 6: Completed.
- Phase 7: Completed.

## Goal

Allow `capital-one-credit-monthly-statement` imports to parse real Capital One
monthly credit card PDFs whose transaction tables are extracted as split column
lines, while preserving support for the existing single-line transaction layout.

The root sample `Statement_052026_5094.pdf` is detectable as a Capital One
credit monthly statement and has a valid statement period, but the current
transaction parser returns no rows because `PDFTextStripper` extracts each table
cell onto separate lines instead of one full transaction row.

## Findings

Current extractor behavior:

- `CapitalOneCreditMonthlyStatementExtractor` reads PDF text with
  `PDFTextStripper`.
- `canHandle(...)` checks for `Credit Card`, `Capital One`, and
  `days in Billing Cycle`.
- `extractStatementPeriod(...)` supports the root sample period shape:
  `Apr 19, 2026 - May 19, 2026`.
- `parseTransactions(...)` is section-aware but only attempts to parse one line
  at a time.
- `TRANSACTION_PATTERN` only supports rows shaped like:
  `Nov 20 Nov 21 ONLINE PAYMENT THANK YOU $500.00`.

Root sample behavior:

- Default extracted text is shaped like:

```text
May 2
May 2
CREDIT-CASH BACK REWARD
- $450.68
```

- `pdftotext -layout` shows the transaction rows are present and recognizable,
  but PDFBox's default text output does not preserve them as single lines.
- The sample includes foreign currency detail lines, exchange-rate lines,
  airline ticket detail lines, continuation pages, payments/credits, purchases,
  and a final transaction immediately before summary totals.

## Phase 1: Prerequisites And Baseline

- Read `../service-common/docs/code-quality-standards.md` before Java edits.
- Read `../service-common/docs/testing-patterns.md` before adding tests.
- Confirm the existing focused test still passes:

```bash
./gradlew test --tests org.budgetanalyzer.transaction.service.extractor.CapOneCreditMonthlyExtractorTest
```

- If `service-common` dependency resolution fails, publish the sibling
  repository to Maven Local and retry:

```bash
cd ../service-common
./gradlew clean build publishToMavenLocal
cd ../transaction-service
./gradlew test --tests org.budgetanalyzer.transaction.service.extractor.CapOneCreditMonthlyExtractorTest
```

## Phase 2: Add Regression Fixture Coverage

- Add a sanitized test fixture that represents the split-column text behavior
  from the root PDF.
- Prefer a generated PDF fixture over checking in the real root PDF, because the
  root PDF contains personal statement data.
- Include rows for:
  - Payments and credits with negative amounts.
  - Normal purchase debits.
  - Foreign-currency detail lines after a transaction amount.
  - Airline ticket detail lines after a transaction amount.
  - `Transactions (Continued)` page continuation sections.
  - A final transaction followed by `Total Transactions` before the amount line.
- Add tests that prove:
  - Existing single-line fixture still parses.
  - New split-column fixture parses.
  - Credits and debits are classified correctly.
  - Transaction dates use the statement-period year correctly.
  - Foreign-currency and travel detail lines are not imported as transactions.

## Phase 3: Parser Shape Selection

- Keep the existing single-line parser as the first parsing path.
- Add an internal fallback parser that runs only when the single-line parser
  returns no transactions.
- Do not change the format key, registry behavior, or statement format seed
  data. This is an extractor compatibility improvement for the same Capital One
  monthly credit format.
- Log which parser shape produced rows at debug level if useful, without logging
  full statement contents or sensitive values.

## Phase 4: Split-Column Parser Implementation

- Implement the fallback as a small state machine over normalized nonblank text
  lines.
- Reuse the existing section detection:
  - `Payments, Credits and Adjustments` means parsed rows are credits.
  - `Transactions` and `Transactions (Continued)` mean parsed rows are debits
    unless their amount is explicitly negative.
- Recognize date-only lines with month and day, for example `May 2`.
- Build a pending row from:
  - Transaction date.
  - Post date.
  - One or more description lines.
  - Amount line, including optional negative sign.
- Treat a new date line as the start of the next row only after the previous row
  has completed.
- Skip known non-transaction detail and footer lines while preserving a pending
  row:
  - Standalone foreign amount lines.
  - `THB`, `HKD`, and exchange-rate lines.
  - `TK#:`, `ORIG:`, `DEST:`, `PSGR:`, `S/O:`, `CARRIER:`, and `SVC:` lines.
  - `Additional Information on the next page`.
  - `Total Transactions`, `Total Fees`, `Total Interest`, fees, interest, and
    year-to-date summary sections.
- Stop parsing transaction rows once the parser reaches fees or interest
  sections and no pending row remains.

## Phase 5: Shared Helpers And Guardrails

- Reuse existing amount parsing, date parsing, bank name, currency code, and
  `PreviewTransaction` construction where possible.
- Keep the fallback private to
  `CapitalOneCreditMonthlyStatementExtractor`; do not introduce shared PDF
  abstractions unless tests show meaningful duplication.
- Keep the old `TRANSACTION_PATTERN` behavior unchanged for existing fixtures.
- Avoid returning an empty preview silently if the statement was detected and a
  transaction table was present but no rows could be parsed. Consider a targeted
  `PDF_PARSING_ERROR` only if this matches existing import error semantics.

## Phase 6: Documentation

- Update `docs/statement-import.md` to note that Capital One monthly credit PDFs
  support both single-line extracted tables and split-column extracted tables.
- Update this plan's implementation status as phases are completed.
- Update any API documentation only if response behavior or supported format
  metadata changes. The expected implementation should not require API contract
  changes.

## Phase 7: Verification

Run focused extractor coverage first:

```bash
./gradlew test --tests org.budgetanalyzer.transaction.service.extractor.CapOneCreditMonthlyExtractorTest
```

Then run broader import-related coverage if touched:

```bash
./gradlew test --tests "*ExtractorTest" --tests "*TransactionImportServiceTest"
```

Finally run the normal service verification:

```bash
./gradlew clean build
```

Manual verification should include previewing the root sample with:

```bash
POST /v1/transactions/preview
format=capital-one-credit-monthly-statement
file=Statement_052026_5094.pdf
```

Expected result:

- The preview returns transaction rows from payments, credits, and purchases.
- The existing Capital One monthly fixture still returns its original rows.
- Foreign-currency detail lines, exchange-rate lines, and travel detail lines are
  excluded from imported transactions.
