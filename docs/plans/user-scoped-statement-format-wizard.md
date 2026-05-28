# User-Scoped Statement Format Wizard

**Status:** Draft
**Service:** transaction-service

## Purpose

Support user-created statement formats without requiring a code change for each
new bank export shape. The product goal is to let a user upload a sample
statement, confirm the parser's inferred transaction table mapping, save that
mapping as a reusable statement format, and then use the normal transaction
import flow with that saved format.

This plan keeps the UX simple while acknowledging that statement parsing is
inherently complex. The service should save deterministic parser revisions from
user-confirmed mappings. It should not promise arbitrary PDF understanding, and
normal imports must not depend on a nondeterministic AI call.

## Design Principles

- Keep the top-level statement format identity stable.
- Hide parser revisions behind that top-level format.
- Try active parser revisions automatically; never ask users to choose a bank
  layout version.
- Save custom formats at user scope by default.
- Use one public identifier model. Do not retain `formatKey` as a second public
  identity for only some use cases.
- Separate parser creation from transaction import.
- Prefer inferred mappings and confirmation over exposing parser internals.
- Persist deterministic parser configuration, not ad hoc parser behavior.
- Import the necessary transaction subset first; optional statement metadata
  must not block transaction import.
- Fail clearly when a file is outside the supported parser shape.

## Supported Transaction Subset

The wizard only needs enough data to create valid transaction rows:

- `date`
- `description`
- `amount`
- debit/credit direction, either explicit or inferred
- `currencyIsoCode`, supplied by the saved format or account default
- `accountId`, supplied by the import flow
- `bankName`, supplied by the saved format

The first version should not require account number, statement period, balances,
categories, merchant enrichment, running totals, or other statement metadata.
Those fields can be added later if they can be inferred reliably.

## Product Flow

### Import Entry Point

The import screen should include a statement format dropdown with:

- recently used statement formats
- the user's saved custom statement formats
- enabled built-in statement formats
- `Create new statement format`

The dropdown should stay personal and short. It should not expose every format
known to the service once the catalog grows. If two visible formats have the
same display name, the UI should disambiguate with small source metadata such as
`Custom` or `System`, not by exposing parser revision details.

### Create New Statement Format

Selecting `Create new statement format` starts a wizard flow:

1. The user uploads a sample statement file.
2. The service extracts candidate transaction tables and inferred mappings.
3. The UI shows the best inferred transaction table in a read-only grid.
4. The user accepts or adjusts column meanings.
5. The UI shows a read-only parsed transaction preview.
6. The user saves the statement format.
7. The user can start the normal import preview using the saved statement
   format.

The read-only parsed transaction preview in step 5 is a parser validation view,
not an import review view. It should not expose add, remove, edit, duplicate
handling, or batch import actions.

### Normal Import

After a statement format is saved, importing transactions uses the existing
preview-to-batch flow:

1. User selects the saved top-level statement format.
2. User uploads the statement file.
3. `/v1/transactions/preview` receives the selected `statementFormatId`.
4. The service tries active parser revisions for that statement format.
5. The service selects one valid parser revision, parses the file, and returns
   an import-ready preview token.
6. User reviews and edits transactions.
7. `/v1/transactions/batch` persists the reviewed transactions.

The preview token should record both the selected `statementFormatId` and the
winning `parserRevisionId`. Batch import should record the same provenance on
`file_import`.

This separation keeps error handling clear. Parser save failures never touch
transactions, and transaction import failures do not partially create or revise
statement formats.

## Identity And Naming

`formatKey` should not remain the public import identity. It is currently used
as a database slug, API path variable, extractor routing key, preview parameter,
preview token field, and `file_import` provenance field. That is too much once
formats can be user-scoped and names can collide.

Use `StatementFormat.id` as the public/import identity:

- `GET /v1/statement-formats/{id}`
- `PUT /v1/statement-formats/{id}`
- `POST /v1/transactions/preview?statementFormatId={id}`

The exact database type can follow the service's normal entity style, but the
important rule is that APIs and imports use the top-level statement format ID,
not a user-editable name and not a slug.

`displayName` is a mutable label:

- not globally unique
- not used for import routing
- not used for `file_import` provenance
- safe to rename without breaking old imports

The service may prevent duplicate active display names for one user's custom
formats as a UX validation, but it should not depend on a database uniqueness
constraint for display names. Promotion and system catalogs make same-name
formats legitimate.

## User Scope And Permissions

Custom statement formats should be user-scoped by default:

- `scope = USER`
- `ownerId = current authenticated user`
- visible to that user only
- enabled immediately after save

Built-in statement formats should be system-scoped:

- `scope = SYSTEM`
- no user owner
- maintained by the application
- visible when globally enabled or enabled by the user

Do not create a separate `userstatementformats` permission resource. Keep the
resource as `statementformats` and use the same scope pattern already used by
transactions:

- `statementformats:read` reads formats visible to the current user.
- `statementformats:write` creates and updates the current user's custom
  formats.
- `statementformats:read:any` reads all user and system formats for admin or
  support workflows.
- `statementformats:write:any` creates and updates system formats, manages
  global visibility, and promotes user formats into system formats.

If the built-in catalog grows, add a lightweight user visibility model:

- user custom formats are visible by default
- built-in formats can be enabled or hidden per user
- dropdown sorting favors recent use
- a management screen can later support search, enable, disable, rename custom,
  archive custom, and duplicate custom format actions

## Hidden Parser Revisions

Parser revisions are internal implementation details. The user selects one
top-level statement format, and the service automatically tries active revisions
for that format.

Example:

```text
Statement format: Capital One Credit Card
Statement format ID: 123
Parser revisions:
  - revision 1: original single-line transaction rows
  - revision 2: split-column transaction rows starting around April 2026
```

Users should not have to remember that PDFs before April 2026 use one layout and
PDFs after April 2026 use another. The service should try both active revisions,
prefer the newest/highest-priority valid match, and record the actual revision
used.

Older revisions should stay available for old files and regression coverage.
When a bank changes its statement layout, the service adds a new hidden revision
under the same visible statement format instead of adding a new dropdown entry.

## Parser Attempts

`ParserAttempt` is an in-memory service-layer value object, not a database
entity. Normal imports should not persist every failed parser attempt.

Each active revision should produce an attempt result such as:

```text
ParserAttempt
  parserRevisionId
  status: NOT_APPLICABLE | MATCHED | FAILED
  parsedRows
  diagnostics
```

The selection rule should be deterministic:

- Try enabled revisions for the selected statement format.
- Prefer newer or higher-priority revisions.
- Treat "parsed some rows" as insufficient by itself; a match must satisfy the
  parser engine's required fields, minimum-row, date, amount, and direction
  checks.
- Reject the upload when no revision matches.
- Reject or return a clear ambiguity error when multiple revisions match in a
  way that cannot be resolved by priority and confidence.
- Persist only the winning `parserRevisionId` on the preview token and
  `file_import`.

Diagnostic details from failed attempts may be logged in sanitized form. A
future admin diagnostic table can be added if support workflows prove that
normal logs are not enough.

## Parser Configuration Storage

Do not use PostgreSQL-specific JSON features. The database should store parser
configuration as an opaque text payload and should not query inside it.

`ParserRevision` should carry queryable metadata in normal columns:

```text
parser_revision
  id
  statement_format_id
  revision_number
  parser_type
  handler_key
  config_schema_version
  parser_config
  priority
  enabled
  promoted_from_parser_revision_id
  created_at
  updated_at
```

Notes:

- `parser_config` is an opaque text/CLOB-style column. It may contain JSON, but
  the database must treat it as text.
- `config_schema_version` tells the application which Java config record to use
  for deserialization and validation.
- `parser_type` selects the parser engine, such as `STATIC_HANDLER`,
  `CSV_COLUMN_CONFIG`, or `PDF_TEXT_TABLE_CONFIG`.
- `handler_key` is internal routing for static Java handlers. It replaces the
  current practice of static extractors claiming public `formatKey` values.
- Fields that must be searched, sorted, filtered, or joined belong in columns,
  not inside `parser_config`.

Example CSV `parser_config`:

```json
{
  "date": {
    "column": "Transaction Date",
    "format": "MM/dd/uu"
  },
  "description": {
    "column": "Description"
  },
  "amount": {
    "mode": "SINGLE_AMOUNT_WITH_TYPE",
    "amountColumn": "Amount",
    "typeColumn": "Transaction Type",
    "creditValues": ["credit", "deposit"],
    "debitValues": ["debit", "withdrawal"]
  }
}
```

Example text-PDF table `parser_config`:

```json
{
  "fileType": "TEXT_PDF",
  "table": {
    "headerMustContain": ["Date", "Description", "Amount"],
    "minimumRows": 3
  },
  "columns": {
    "date": {
      "header": "Date",
      "format": "MMM d"
    },
    "description": {
      "header": "Description"
    },
    "amount": {
      "header": "Amount",
      "negativeMeans": "CREDIT"
    }
  },
  "dateContext": {
    "yearSource": "STATEMENT_PERIOD"
  }
}
```

The parser engine should deserialize `parser_config` into typed Java records,
validate it, and execute it. The database should only persist and retrieve it.

## PDF Parser Scope

The custom PDF wizard should target text-based PDFs with transaction-like
tables. The parser should be smart enough to infer likely transaction tables,
but the supported shape should stay narrow:

- one transaction table per parser revision
- date column
- description column
- one signed amount column, or separate debit and credit amount columns
- optional type column
- deterministic date and amount parsing
- default bank and currency from the saved statement format

The parser should infer rows and columns from extracted PDF text, then let the
user confirm the mapping. The UI should not expose low-level concepts such as
anchors, regexes, page regions, or parser rules in the first version.

Reject unsupported files with clear messages when:

- no confident transaction table is found
- too few valid transaction rows are detected
- date or amount columns cannot be inferred or confirmed
- debit/credit direction remains ambiguous after the simple user question
- the PDF is scanned or OCR-dependent

## CSV Parser Scope

CSV custom formats are lower risk and can build directly on the existing
configuration-driven CSV model documented in
[Statement Import System](../statement-import.md).

The wizard should show detected headers and sample rows, then ask the user to
map required fields by choosing columns rather than typing exact header names.
Saving the wizard result should create a user-scoped `StatementFormat` and an
active `ParserRevision` with `parser_type = CSV_COLUMN_CONFIG`.

## Backend Shape

The implementation should separate these concepts:

- `StatementFormat`: user-facing saved format and import selection target.
- `ParserRevision`: hidden deterministic parser configuration or static handler
  path.
- `ParserAttempt`: transient in-memory parse result used during revision
  selection.
- `UserStatementFormatVisibility`: optional future table for per-user visibility
  of system formats.
- `FileImport`: import history, extended to record `statementFormatId` and
  `parserRevisionId`.

Possible parser creation endpoints:

- create parser setup session from a sample file
- return inferred table candidates and mappings
- update mapping choices for the session
- return read-only parser preview rows
- save statement format and active parser revision

Existing transaction import endpoints should stay responsible for import-ready
preview and batch persistence. The preview endpoint should accept
`statementFormatId`, not `format`.

## System Promotion

Promotion should clone a user format into a system format. It should not move,
rewrite, or take ownership of the user's personal format.

Promotion creates:

- a new `StatementFormat` with `scope = SYSTEM` and `ownerId = null`
- a copied parser revision under the new system format
- provenance fields such as `promoted_from_statement_format_id` and
  `promoted_from_parser_revision_id`

The copied system revision must be independent. If the user later edits,
archives, or deletes the personal format, the promoted system format should keep
working.

For the first promotion workflow, promote the current active/winning revision by
default. If user formats later support multiple active revisions, the admin UI
can allow maintainers to choose which revisions to copy.

Promotion requires `statementformats:write:any`. The promoted system format
should not automatically replace or hide the user's personal format. The user
can later choose to hide, archive, or switch away from their custom copy.

## AI Assistance

AI can be considered later as an optional setup assistant that proposes a
`parser_config` from an uploaded sample. The saved artifact must still be a
deterministic parser revision that the Java parser engine validates and runs.

Normal transaction import must not call AI. A future AI-assisted setup flow
still needs user confirmation, deterministic validation, and persisted
`parser_config`.

## Non-Goals

- Fully automatic arbitrary PDF understanding.
- AI-dependent transaction import.
- User-visible parser DSL or advanced rule editor.
- OCR support in the first version.
- Extracting account numbers, balances, statement periods, or running totals as
  required fields.
- Combining statement format save and transaction import in one submit.
- Exposing bank layout versions as separate dropdown entries.
- Persisting every failed parser attempt during normal import.
- Maintaining `formatKey` as a parallel public identity.

## Phased Implementation

### Phase 1: Model, Identity, And Visibility

- Replace public `formatKey` usage with `StatementFormat.id` in statement-format
  APIs, import preview requests, preview tokens, frontend selects, and
  `file_import` provenance.
- Add `scope` and `ownerId` to statement formats.
- Add hidden parser revisions.
- Move existing CSV configuration into parser revisions.
- Represent existing static PDF handlers as parser revisions with internal
  `handler_key` values.
- Keep dropdown behavior simple: system formats plus user formats, sorted by
  recent use when available.
- Apply permission rules with `statementformats:read`,
  `statementformats:write`, `statementformats:read:any`, and
  `statementformats:write:any`.

### Phase 2: Revision Selection

- Change import preview to load the selected statement format by ID.
- Try all active parser revisions for that format.
- Add transient `ParserAttempt` result handling.
- Persist the winning parser revision in preview tokens and `file_import`.
- Add fixture-based regression tests for multi-revision formats, including the
  Capital One monthly credit PDF layout change.

### Phase 3: CSV Wizard

- Add a create-format wizard for CSV.
- Infer columns from headers and sample values.
- Show read-only parser preview.
- Save user-scoped CSV statement format with a deterministic
  `CSV_COLUMN_CONFIG` parser revision.

### Phase 4: Generic PDF Table Wizard

- Add text-based PDF table extraction and scoring.
- Infer likely date, description, amount, debit, credit, and type columns.
- Show read-only parser preview.
- Save user-scoped PDF statement format with a deterministic
  `PDF_TEXT_TABLE_CONFIG` parser revision.

### Phase 5: System Promotion And Catalog Management

- Let maintainers clone proven user formats into system templates after review.
- Add per-user enable or hide controls for system templates if the catalog
  becomes too large.
- Add tooling for parser revision diagnostics and fixture-based regression
  tests.

## Open Questions

- Should a user custom format be shareable with another user, or only promotable
  to a reviewed system format?
- Should parser setup sessions store sample files temporarily, or only extracted
  normalized table data?
- What retention and consent rules are required if users submit statements for
  support or system-template promotion?
- Should support users be able to force a parser revision for diagnostics, or
  should revision selection always be automatic outside test tooling?
- Should the application prevent duplicate active display names per user as UX
  validation, or only disambiguate duplicates in the dropdown?
