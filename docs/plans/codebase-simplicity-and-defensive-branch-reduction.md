# Codebase Simplicity and Defensive Branch Reduction Plan

This plan simplifies `transaction-service` without changing its supported HTTP APIs, authorization
rules, persistence semantics, import results, duplicate decisions, or saved-view membership
behavior. The review covered all production Java sources, their tests, database migrations, and the
nearest behavior documentation. The default execution track removes duplicated validation,
test-only production surfaces, repeated conversions, repeated parser work, and branches that defend
states already excluded by an enforced boundary. Requirement-level reductions are listed separately
for product discussion and are not authorized by this plan.

The reviewed baseline contains 13,315 lines of production Java and 194 explicit
`Objects.requireNonNull`, `== null`, or `!= null` sites. That count is an inventory, not a target:
many of those checks represent optional request fields, external file failures, authorization, or
persistence boundaries and must remain. The largest concentrations are the PDF/CSV wizard and
extractor stack, transaction specifications, statement-format management, and saved views. The
baseline `./gradlew clean build` succeeds before any planned change.

## Audit conclusions

### Simplification rules

1. Validate a rule once in the layer that owns it. Bean Validation owns HTTP request shape;
   services own authorization, ownership, persistence state, and cross-field business invariants;
   parsers own untrusted file and persisted parser-configuration validation.
2. Treat internal parameters as non-null by convention after an enforced boundary. Do not add a
   fallback that turns an impossible null into an empty filter, empty criteria, or empty JSON value.
3. Parse an uploaded file once per attempted parser revision. Detection and extraction may be two
   logical steps, but they must operate on the same parsed content.
4. Keep a single normalized representation for a concept. In particular, duplicate candidate
   identity must not be converted through two records that repeat the same null checks.
5. Production code must serve a production call path. Tests should exercise production entry
   points rather than require compatibility overloads, cache-inspection methods, or unused entity
   conversion APIs.
6. A new helper or abstraction is justified only when it removes repeated policy or repeated work.
   Do not replace branches with a framework, strategy hierarchy, or generic validation layer.

### Defensiveness that must remain

- Claims-based authorization, owner checks, and not-found responses that avoid leaking the
  existence of another user's resources.
- Soft-delete filters and owner scoping in transaction and saved-view queries.
- Preview-token version, Base64, authenticated-decryption, payload, owner, and expiry validation.
- Multipart filename, empty-file, read-failure, malformed CSV/PDF, scanned-PDF, and ambiguous
  amount handling at file boundaries.
- Parser configuration deserialization and semantic validation when configuration is loaded from
  the database.
- Fixture-backed PDF layout fallbacks, including the Capital One monthly split-column parser and
  Bangkok Bank positioned-table handling, until real fixtures prove a branch is obsolete.
- Advisory duplicate detection during preview and authoritative duplicate detection during batch
  import. The second check protects against user edits and database changes between calls.
- Authoritative transaction-date validation during batch import, even when a parser reports the
  same rule earlier for better preview feedback.
- Currency semantics, sort-field allowlisting, escaped SQL `LIKE` patterns, parser failure versus
  not-applicable distinctions, and idempotent removal/unhide operations.

### Behavior-preserving findings

| Area | Evidence of unnecessary complexity | Planned simplification |
| --- | --- | --- |
| Batch boundary | `BatchImportRequest.previewImportToken` is `@NotBlank`, then `TransactionController` checks it again, and `TransactionService` null-checks the verified `BatchFileImportSource`. The batch date is `@NotNull`, but the service conditionally skips date rules for null. | Trust the validated controller contract after token verification; retain only token cryptographic validation and service-owned date rules. |
| Statement-format and wizard validation | API records already require display name, bank, currency, mapping, amount mode, and year source. Services repeat those presence checks before applying real ISO, header, mapping, and parser rules. | Remove only repeated request-shape checks. Retain conditional column rules, ISO validity, scope authorization, sample-row validation, and persisted-config validation. |
| Search criteria | `TransactionCriteria.fromFilter`, `fromViewCriteria`, and `TransactionSpecifications.withCriteria` each accept null by silently creating empty criteria. Sets are normalized in `TransactionCriteria` and then filtered again in the specification. Text predicate helpers check states made impossible by trim/split and local construction. | Establish non-null criteria at the controller/domain boundary, normalize collection values once, and make the specification consume that contract directly. |
| Saved-view persistence | Create requests and the database require criteria, pinned IDs, and excluded IDs, yet API mapping, entity setters, and converters repeatedly turn null/blank values into empty objects or JSON. | Keep empty collections as normal values, but stop fabricating them from impossible null/corrupt database states. Fail at the actual persistence boundary. |
| Saved-view resolution | Count and membership repeat the same matching/pinned/excluded set algorithm. Pinned, excluded, bulk-pin, and bulk-exclude paths issue one transaction lookup per ID. | Resolve membership once, derive count from that result, and use owner-scoped active bulk lookups while preserving ordering, deduplication, and not-found behavior. |
| Bulk transaction deletion | The method nests optional/owner branches and performs a read and save per ID. | Resolve active transactions in bulk, partition allowed/not-found IDs once, mark allowed entities, and save them together. Characterize duplicate input IDs before changing the loop. |
| Duplicate candidate pipeline | Financial identity is converted from `PreviewTransaction` to `TransactionDuplicateCandidateKey`, to `TransactionDuplicateCandidateCriteria`, and back, with repeated non-null and amount canonicalization checks. | Use one immutable normalized identity value across service and repository code and canonicalize amount once. |
| Description matching result | Production code consumes only `matched`, but the result carries similarity score, candidate ID, and candidate description plus defensive score validation. Repository candidate IDs exist only to populate that unused result. | Return a boolean while keeping the exact normalization, numeric-token, minimum-length, threshold, and Levenshtein decisions. |
| Extractor lifecycle | The registry calls `canHandle` and then `extract`. Configurable PDF performs full text extraction twice; static PDF handlers open the document for detection and again for extraction; Bangkok Bank opens it twice during detection and again during extraction. CSV detection manually parses the first line before the real CSV parser runs. | Give extractors one attempt operation that detects and extracts from the same parsed content, returning not-applicable separately from a matched parse failure. |
| Dynamic extractor cache | Preview already queries the active revisions, but the registry also maintains CSV/PDF caches, loads CSV revisions at startup, and requires format services to refresh the cache after writes. | Construct a lightweight dynamic extractor from the selected revision during the attempt. Keep only an immutable static-handler lookup. |
| Extractor entity path | `StatementExtractor.extractEntities` is not used by batch import; every implementation duplicates preview-to-entity mapping and tests are its only callers. | Remove the interface method, implementations, and tests. Batch import remains the single authoritative entity mapping path. |
| CSV internals | A map caches one primary and at most one simplified date formatter. Failed parsing retries even when the simplified format is identical. `canHandle` has a second, less-correct header parser. | Store primary/optional fallback formatters directly and parse CSV/header data once with the shared parser. |
| Configurable PDF internals | Mapped headers are established before row parsing, yet value lookup defends a missing mapped index. Minimum row count is checked before and after deterministic row mapping. The date-format validator both whitelists known constants and catches construction failure for the same constants. | Enforce each invariant once while retaining row-width/null checks for malformed external data. |
| Production APIs used only by tests | `TransactionService.createTransaction/createTransactions`, multipart hash/tracking overloads, duplicate-file rejection, extractor enumeration, specification compatibility overloads, and several repository finders have no production callers. `ViewTransactionResponse` and `MembershipType` are unused. | Reconfirm with call-site search, remove them, and rewrite tests around real production paths. |
| Parser attempt DTO | `statementExtractor` and diagnostic text are stored on attempts but not used to produce preview behavior. | Keep revision, status, transactions, and failure only; log sanitized context at the point of failure if operationally useful. |
| Controller and response noise | Search controllers use five helper methods solely to log whether filter groups are present. Response DTOs carry validation annotations that are not invoked for responses. Statement-format response mappers repeat the same fields. | Log request/page context directly, remove unenforced response validation, and share one response mapper. |

### Requirement-level complexity for discussion

These choices deliberately change supported behavior or API shape. They require a decision and a
separate follow-on plan; none is part of the numbered phases below.

| Requirement | Why it is expensive | Simplification options | Audit recommendation |
| --- | --- | --- | --- |
| Generic user-defined PDF wizard | Roughly 1,800 production lines across heuristic table extraction, scoring, inference, warnings, configurable parsing, and validation, before controllers/DTOs/tests. The original wizard plan explicitly calls arbitrary PDF handling inherently complex. | Keep it; remove only automatic analysis and require explicit mapping; or support only dedicated fixture-backed bank PDF handlers. | If custom PDF is not a core product differentiator, retain dedicated handlers and remove the generic wizard. If it is core, accept most of this complexity as essential. |
| Multiple active parser revisions | Every preview tries all enabled revisions in priority/revision order and models matched/not-applicable/failed attempts. `priority`, `configSchemaVersion`, and promotion provenance are effectively fixed or unused in current application flows. | Enforce one enabled revision per format until a real rollover/catalog workflow exists; retain revision provenance for imports. | Prefer one enabled revision unless overlapping bank-layout rollouts are an active requirement. Do not remove provenance. |
| CSV/PDF inference endpoints | Confidence maps, warnings, rejection reasons, and header heuristics support setup convenience, not deterministic import. CSV preview currently returns an always-empty warnings list; PDF preview synthesizes minimal diagnostics. | Return raw headers/candidates and require user mapping; remove confidence/warnings; or remove analyze endpoints and keep preview/save. | Explicit mapping plus preview is the simpler stable contract. |
| Wizard error-field inference | Wizard services inspect exception message text for words such as `date`, `amount`, and `type` to choose a form field. PDF analysis also returns unsupported input as `200 OK` with rejection reasons. | Use one generic `mapping` field and normal 422 failures, or invest in typed parser errors. | Prefer a generic field and consistent error status; a typed error hierarchy is not justified only for form cosmetics. |
| Saved-view pins/exclusions | JSON ID sets, ownership/active filtering, matched/pinned/excluded set algebra, six mutation routes, bulk partial-success semantics, and live counts make saved views much more than saved filters. | Keep all behavior; remove single-item routes in favor of bulk; remove embedded live counts; or make views pure filters. | First remove live counts from ordinary view responses. If pins/exclusions have low usage, pure saved filters are the largest simplification. |
| Live saved-view transaction count | Listing or mapping each view can trigger matching, pinned, and excluded queries, producing an N-times query pattern. | Separate count endpoint, omit count from list responses, or implement a more complex batch-count query. | Omit live count from general responses rather than add batch query machinery. |
| Fuzzy duplicate matching | Levenshtein scoring, minimum length, numeric token ordering, exact financial candidate queries, preview metadata, batch overrides, and tests form a substantial policy surface. | Keep current policy; use normalized exact descriptions only; or make fuzzy matching advisory and never auto-skip. | Normalized exact matching is simpler and safer against false-positive financial skips unless production evidence demonstrates fuzzy value. Keep preview/batch rechecks whichever comparison rule is selected. |
| Extracted category | CSV/PDF parsers, mappings, and preview/batch DTOs carry category, but `Transaction` has no category field and batch mapping discards it. | Persist category as a real transaction feature, or remove it end to end. | Remove it unless category persistence is planned; the current middle state has complexity without durable functionality. |
| Server-side format hide/unhide | A preference entity, migration, repository, endpoints, permissions, and list filtering support personal catalog hiding. | Keep cross-device preference, or let the client filter locally. | Remove server-side preference only if cross-device persistence is not required. |
| Direct format creation plus wizards | The generic CSV create endpoint and CSV wizard save are two creation routes with overlapping validation and persistence. | Make one route canonical; keep raw creation only for admin/system use or move inference fully client-side. | Choose one user-facing creation path and make the other explicitly administrative. |
| Unsupported `XLSX` enum value | The schema and enum advertise XLSX, while no parser or creation flow supports it. | Remove it after checking persisted rows and clients, or implement it. | Remove the value if it is only roadmap residue. |
| Static PDF fallbacks | Capital One and Bangkok handlers contain layout-specific fallback branches and substantial parsing code. | Support fewer statement variants. | Do not simplify requirements here without real export fixtures and usage data; these branches are evidence-backed defensiveness. |

## Phase 1: Lock Behavior and Validation Ownership

### Goal

Create a refactoring safety net that states which layer owns each invariant and captures any
currently under-tested behavior that later phases must preserve.

### Scope

- Reconfirm every candidate production method's callers with `rg`, including reflective Spring and
  JPA-derived repository usage.
- Add or strengthen characterization tests for:
  - missing/blank batch token handling through MockMvc;
  - statement-format and CSV/PDF wizard request-shape versus business-validation errors;
  - null-free search and empty-filter semantics through controller/service production paths;
  - duplicate input IDs, partial success, owner isolation, and soft deletes in bulk operations;
  - saved-view matched/pinned/excluded/count equivalence;
  - parser revision ordering, not-applicable versus failed behavior, and all fixture-backed PDF
    fallbacks;
  - quoted, BOM-bearing, and extra-column CSV headers using the actual shared CSV parser, recording
    current accepted/rejected behavior rather than silently expanding scope.
- Record validation ownership in the nearest affected test names and Javadocs: API shape at request
  records, business invariants at services, external content/configuration at parsers.

### Non-goals

- No production branch removal.
- No API, schema, parser, or requirement change.
- No broad coverage target or test rewrite unrelated to the planned simplifications.

### Required context

- Repository `AGENTS.md`, especially Architectural Simplicity and prerequisite rules.
- `../service-common/docs/code-quality-standards.md` and
  `../service-common/docs/testing-patterns.md`.
- `docs/statement-import.md`, `docs/duplicate-detection.md`, and `docs/saved-views.md`.
- The audit conclusions and protected-defensiveness list in this plan.

### Implementation notes

- Prefer existing unit/integration test classes and real parser fixtures.
- Assert externally meaningful status, error code, field path, ownership, membership, and parsed
  values. Do not freeze incidental private helper structure or exact log text.
- Where two current checks produce the same HTTP result, add one boundary-level test rather than a
  separate service test for the redundant check.
- If current behavior is ambiguous, stop and record the ambiguity instead of choosing a new policy.
- Update affected documentation in this phase if characterization exposes a mismatch; do not defer
  it to the final phase.

### Validation

Run the repository-required sequence:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Re-run call-site inventories with `rg` and confirm every proposed deletion has no production caller.

### Completion criteria

- The baseline behaviors named in Scope are covered through production entry points.
- Every proposed validation removal has a named surviving owner and test.
- All fixture-backed static and configurable import paths remain green.
- Documentation and tests agree on current behavior.

## Phase 2: Remove Dead and Test-Only Production Surfaces

### Goal

Delete production APIs and types that do not participate in a production call path, reducing code
and tests before changing active flows.

### Scope

- Remove `TransactionService.createTransaction` and `createTransactions` if Phase 1 reconfirms that
  only unit tests call them.
- Remove the `MultipartFile` hash/check overloads and `checkAndRejectDuplicate`; retain the byte-array
  hash/check path used after a file has been read once.
- Remove `StatementExtractor.extractEntities` from the interface and every implementation. Delete
  duplicate preview-to-entity mappers and update tests to use preview extraction plus the real batch
  import path.
- Remove `StatementExtractorRegistry.getAllExtractors` and cache-inspection tests.
- Remove repository methods used only as integration-test fixture discovery, including the unused
  statement-format finders, after reconfirming there is no Spring/runtime caller.
- Remove `ViewTransactionResponse` and `MembershipType` if still unreferenced.
- Remove test-only compatibility/convenience methods such as
  `TransactionSpecifications.withFilter`, `TransactionFilter.empty`, and unused duplicate-key
  factories; update tests to call `TransactionCriteria.fromFilter` and `withCriteria` as production
  does.
- Remove unused constants, imports, and Javadocs revealed by these deletions.

### Non-goals

- Do not remove an HTTP endpoint, database column, migration, enum value, parser revision field, or
  documented product behavior.
- Do not merge active services merely to reduce class count.
- Do not change preview or batch entity mapping semantics.

### Required context

- Phase 1 call-site inventory and characterization tests.
- `TransactionService`, `FileHashService`, `FileImportTrackingService`, `StatementExtractor`, all
  extractor implementations, and `StatementFormatRepository`.
- `../service-common/docs/code-quality-standards.md`.

### Implementation notes

- Use `rg` before each removal; derived Spring Data method names still count as production API only
  when an application call site exists.
- Tests must not retain a production method solely as a setup convenience. Persist fixtures through
  repositories or call the real boundary being tested.
- Keep `FileImportTrackingService.checkHash`, `checkFile(byte[], ...)`, and `recordImport`; exact-file
  status and provenance remain supported.
- Update nearby Javadocs and any documentation that refers to a removed internal path in the same
  phase.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Use `rg` to confirm removed symbols and their stale documentation no longer exist.

### Completion criteria

- No listed test-only production surface remains without a documented production caller.
- Import preview and batch tests prove that entity persistence still has one authoritative path.
- The full build passes with no replacement compatibility wrappers.

## Phase 3: Establish Single-Owner Null and Validation Contracts

### Goal

Remove repeated null/presence branches after enforced boundaries while preserving all business and
external-boundary validation.

### Scope

- Remove the controller's duplicate `previewImportToken` guard, the service's repeated verified
  `BatchFileImportSource` null check, and the unreachable null branch around batch date rules.
- Where `@NotEmpty` and `@NotNull` duplicate collection presence, use one enforced constraint while
  retaining the documented HTTP error field and status.
- In statement-format and wizard flows, remove presence checks already enforced by the `@Valid`
  request and all production callers. Retain:
  - scope permission and writable-owner rules;
  - valid ISO 4217 currency checks;
  - format-type support;
  - conditional amount/header/date rules;
  - uploaded sample/header/row validation;
  - parser configuration validation after database deserialization.
- Make create flows construct the already-validated CSV format and initial parser revision directly,
  removing repeated format-type/date-header branches that cannot be reached.
- Remove the unused `canWriteAny` argument from CSV wizard save if the command remains unconditionally
  user-scoped.
- Establish saved-view criteria and ID sets as non-null domain/persistence invariants. Remove null-to-
  empty fallbacks from `ViewCriteriaApi.from`, `TransactionCriteria.fromViewCriteria`, entity
  setters, and converters when the API and database already prohibit null.
- Keep patch-field null checks because null intentionally means "not updated".

### Non-goals

- Do not remove cryptographic token checks, ownership checks, parser config validation, optional
  patch/filter semantics, or corrupt JSON parse failures.
- Do not introduce method-validation proxies or annotate every internal parameter.
- Do not replace straightforward conditional business validation with a custom annotation
  framework.

### Required context

- Phase 1 validation-ownership tests.
- `BatchImportRequest`, `BatchImportTransactionRequest`, wizard request records, and statement-format
  request records.
- `TransactionController`, `TransactionService`, `StatementFormatService`, CSV/PDF wizard services,
  `SavedView`, `ViewCriteriaConverter`, and `LongSetConverter`.
- The Null Handling and Validation Contracts section of
  `../service-common/docs/code-quality-standards.md`.

### Implementation notes

- Trace every service entry from all production callers before removing a check. A second adapter,
  scheduler, or message consumer would make service validation necessary; none should be assumed
  absent without search evidence.
- Preserve HTTP error codes and field paths. Exact duplicate wording may be consolidated only when
  it is not a documented contract and characterization tests prove the boundary remains clear.
- Empty criteria and empty ID sets remain valid explicit values. Only impossible null/blank fallback
  behavior is removed.
- A database null/blank that violates a `NOT NULL`/JSON invariant should fail visibly rather than
  broaden a query by becoming an empty filter.
- Update affected API/behavior documentation and Javadocs in this phase.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Search the affected call chains for repeated `requireNonNull`, null-to-empty, and required-field
checks, then verify each remaining check has a boundary or optional-value rationale.

### Completion criteria

- Each listed invariant is enforced in one owning layer.
- Valid HTTP behavior and all business errors remain unchanged.
- Saved-view persistence no longer silently converts impossible null/corrupt state to empty state.
- No speculative validation framework or new cross-layer DTO translation is introduced.

## Phase 4: Simplify Search and Duplicate Detection Data Flow

### Goal

Use one normalized criteria contract and one duplicate identity representation, eliminating repeated
normalization, conversion, and result metadata that production does not consume.

### Scope

- Require non-null `TransactionCriteria` in `TransactionSpecifications.withCriteria`; remove its
  fallback to `empty`.
- Normalize multi-value filter sets once in `TransactionCriteria`. Remove second-pass null/blank
  filtering in specification helpers.
- Simplify text predicates after the nonblank trim/split boundary: remove impossible blank-word,
  null-local-list, and empty-list branches while retaining case-insensitive multiword OR behavior
  and wildcard/backslash escaping.
- Retain exact, range, timestamp, owner, and soft-delete semantics.
- Replace `TransactionDuplicateCandidateKey` plus `TransactionDuplicateCandidateCriteria` with one
  neutral immutable identity value used by service grouping and repository query construction.
- Canonicalize amount scale once when the identity is created.
- Make description matching return the boolean that production consumes. Remove candidate ID,
  candidate description, score-result validation, and repository projection fields used only to
  populate that internal result.
- Retain the current normalized exact and fuzzy comparison algorithm byte-for-byte in meaning:
  threshold, minimum length, Unicode/diacritic handling, numeric token equality/order, and
  Levenshtein calculation.
- Remove repeated empty-input guards inside private repository/matcher methods once the public
  callers establish nonempty batches; retain one guard if a genuine production caller permits an
  empty collection.

### Non-goals

- Do not adopt the requirement option to remove fuzzy matching.
- Do not change candidate financial fields, owner scoping, account-ID exclusion, `allowDuplicate`,
  preview reasons, or skipped/imported counts.
- Do not move `TransactionFilter` merely to remove its documented intentional API crossing.

### Required context

- Phase 1 duplicate and search characterization tests.
- `TransactionCriteria`, `TransactionSpecifications`, `TransactionDuplicateMatcher`,
  `TransactionDescriptionMatcher`, duplicate candidate types, and `TransactionRepository`.
- `docs/duplicate-detection.md`, `docs/database-schema.md`, and the intentional
  `TransactionFilter` crossing documented in `AGENTS.md`.

### Implementation notes

- Put the shared duplicate identity in a neutral package usable by service and repository code; do
  not create service-to-API or repository-to-service layering violations.
- The identity type represents already-validated API/DB data. Do not repeat five null checks in its
  constructor and again during each conversion.
- Keep the zero-length similarity case if punctuation-only, nonblank descriptions can still reach
  it; that is a reachable edge, not speculative defense.
- Update duplicate detection and database documentation in the same phase to describe the single
  identity representation without changing policy.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Compare pre/post integration results for blank/multiword/wildcard search and all description matcher
fixtures. Verify duplicate repository SQL still uses structured parameters rather than string-built
SQL values.

### Completion criteria

- Search criteria is never silently replaced with empty criteria after construction.
- Filter sets and duplicate amounts are normalized exactly once.
- One duplicate identity type crosses the service/repository pipeline.
- Duplicate decisions and public preview/batch metadata are unchanged.

## Phase 5: Unify Saved-View Membership and Bulk Resolution

### Goal

Calculate saved-view membership once and replace per-ID branching/query loops with bulk owner-scoped
resolution, preserving current partial-success and security behavior.

### Scope

- Make one internal membership resolver produce matched, pinned, and excluded active owner-scoped
  IDs. Derive `countViewTransactions` from the same effective membership instead of repeating set
  algebra.
- Fetch the union of pinned and excluded IDs in one active owner-scoped query and partition the
  result in memory.
- Add one small saved-view helper that resolves requested IDs into unique valid IDs and not-found
  IDs for both bulk pin and bulk exclude.
- Preserve input-order and duplicate-ID behavior documented by current endpoints.
- Reuse the same owner-scoped active lookup for single pin/exclude where doing so simplifies code;
  keep unpin/unexclude idempotent without requiring the transaction to still exist.
- Refactor bulk transaction soft-delete to fetch active candidates in bulk, apply `canActOnAny` and
  owner rules once, mark entities, and call `saveAll` once.
- Preserve 404/non-leak behavior, soft deletes, partial success, and transaction boundaries.

### Non-goals

- Do not remove pins, exclusions, single-item endpoints, bulk endpoints, or embedded counts.
- Do not normalize saved-view membership into a new database table.
- Do not build a generic bulk-operation framework shared across unrelated services.

### Required context

- Phase 1 saved-view and bulk-operation characterization tests.
- `SavedViewService`, `SavedView`, `SavedViewController`, `TransactionService`, and
  `TransactionRepository`.
- `docs/saved-views.md`, `docs/api/README.md`, and `docs/database-schema.md`.

### Implementation notes

- Use one explicit repository query/specification for active IDs and owner scope. Do not fetch all
  entities and filter security in an unbounded application-side query.
- Retain distinct membership semantics: matched excludes active excluded IDs; pinned contains active
  pinned IDs not already matched; excluded reports active excluded IDs; effective count is matched
  plus pinned.
- Avoid parallel streams or clever collectors; small named sets/maps are clearer.
- Update affected saved-view and bulk-operation documentation in this phase, including query
  behavior only where it is operationally relevant.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Use integration tests or SQL query counting to demonstrate that pinned/excluded and bulk operations
no longer perform one lookup per ID. Confirm foreign and soft-deleted IDs remain indistinguishable
from missing IDs to unauthorized callers.

### Completion criteria

- Membership and count use one source of truth.
- Saved-view and bulk-delete operations use bounded bulk queries/writes rather than N per-ID calls.
- Partial-success lists, deduplication, ordering, authorization, and soft-delete behavior match the
  Phase 1 baseline.

## Phase 6: Make Parser Attempts Single-Pass and Stateless

### Goal

Remove the detect-then-reparse lifecycle and dynamic extractor cache while retaining parser revision
ordering and failure semantics.

### Scope

- Replace `StatementExtractor.canHandle` plus `extract` with one attempt/extract contract that
  receives content, filename, and account ID.
- Use one simple outcome convention:
  - not applicable when file type/signature/mapped table does not match;
  - matched with nonempty preview transactions;
  - failed when a matching parser encounters malformed content or invalid persisted config.
- Simplify `ParserAttempt` to revision, status, transactions, and failure. Remove the unused
  extractor and diagnostic fields.
- In configurable CSV, parse with `CsvParser` once, validate mapped headers against the resulting
  headers, and map the same rows.
- In configurable PDF, create one `PdfTextDocument`, select candidates, and parse those candidates
  without a second extraction.
- For each static PDF handler, load/extract its full parsing representation once, perform its bank/
  statement signature detection on that representation, then parse it. Retain all fixture-backed
  layout strategies.
- Remove CSV/PDF dynamic extractor caches, startup revision scanning, cache refresh methods, and
  `StatementFormatService` refresh coupling. Construct dynamic extractors from the revision being
  attempted.
- Build an immutable static handler-key map once from injected extractors.
- Continue trying every enabled revision in repository priority/revision order.

### Non-goals

- Do not reduce supported banks, PDF layouts, parser types, or active revision behavior.
- Do not create a universal parsed-file hierarchy or load every possible PDF representation up
  front.
- Do not swallow a matched parser's failure as not-applicable.
- Do not remove persisted parser configuration validation.

### Required context

- Phase 1 parser characterization and all real file fixtures.
- `StatementExtractor`, `StatementExtractorRegistry`, `ParserAttempt`, configurable extractors,
  static PDF extractors, `PdfTextExtractionService`, and parser revision repositories.
- `docs/statement-import.md` and the parser attempt/revision rationale in
  `docs/plans/user-scoped-statement-format-wizard.md`.

### Implementation notes

- Prefer a direct result/empty convention over another strategy hierarchy. Reuse `ParserAttempt`
  at the registry boundary rather than creating overlapping attempt DTOs.
- Extension mismatch can return not-applicable before parsing. Once the content signature matches,
  parsing errors must remain failures so corrupt statements are diagnosable.
- For Bangkok Bank, derive signature text from the once-extracted positioned lines if fixture tests
  prove equivalence; do not introduce a second PDF load merely for detection.
- Static handler key duplication should fail during initialization because it is a real application
  configuration error.
- Update statement import architecture/Javadocs in this phase to describe single-pass attempts and
  stateless dynamic construction.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Add test instrumentation around the CSV parser and PDF extraction service/document loader to assert
one parse/load per attempted revision. Verify parser attempt ordering and selected revision IDs are
unchanged.

### Completion criteria

- No extractor exposes `canHandle` followed by a second parse.
- Configurable CSV/PDF and every static handler parse once per revision attempt.
- Dynamic caches, refresh lifecycle, and startup revision scan are gone.
- Active revision selection, failure codes, preview rows, and fixtures remain unchanged.

## Phase 7: Remove Redundant Extractor and Wizard Branches

### Goal

Simplify active parser internals after the single-pass contract makes their invariants explicit,
without reducing supported statement variants.

### Scope

- Replace the CSV date formatter map with a primary formatter and an optional distinct simplified
  formatter. Retry only when the configured pattern actually contains a removable time component.
- Remove catch/rethrow blocks that add no context; keep exception translation at the external parser
  boundary.
- In configurable PDF, enforce minimum row count once when candidate rows are selected. Retain row
  width/null checks for malformed external rows, but remove missing-header branches after mapped
  headers have been proven.
- In PDF config validation, choose one date-format validity mechanism. Because the supported list is
  fixed, either trust its tested constants or validate arbitrary patterns, but do not whitelist and
  reconstruct defensively.
- Remove unused parser constants/captures and duplicated locals revealed by the refactor.
- Consolidate identical small mapping/config construction helpers only where there are at least two
  active production callers.
- Retain CSV/PDF business rules and field-addressable wizard errors exactly as characterized; the
  message-sniffing and diagnostics API reductions remain requirement decisions.

### Non-goals

- Do not remove `headerMustContain`, confidence, warnings, diagnostics, category, fuzzy matching, or
  layout fallbacks in this behavior-preserving phase.
- Do not apply batch date validation to parser types that did not previously apply it.
- Do not merge all bank-specific extractors into the generic PDF table engine.

### Required context

- Phase 6 single-pass implementations and parser instrumentation.
- `ConfigurableCsvStatementExtractor`, `ConfigurablePdfTextTableStatementExtractor`,
  `PdfTextTableParserConfigValidator`, both wizard services, and static extractor tests.
- `docs/statement-import.md` and real bank fixtures.

### Implementation notes

- Before deleting a branch, identify the enforced predecessor that makes it unreachable and name it
  in a test or concise comment where non-obvious.
- Do not centralize preview and batch date checks merely because they have the same thresholds; they
  execute at different trust points and both are useful.
- If two PDFBox extraction implementations produce materially different fixture text, keep them
  separate and document why instead of forcing a leaky common abstraction.
- Update affected import documentation and Javadocs in this phase.

### Validation

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Run all CSV/PDF fixtures through both wizard preview and normal transaction preview. Compare dates,
amounts, types, descriptions, category preview values, account IDs, selected revision, and error
codes with the Phase 1 baseline.

### Completion criteria

- Formatter, header, and minimum-row invariants are each enforced once.
- No supported parser layout or wizard response behavior changes.
- Remaining external-data branches have a concrete malformed-input or fixture rationale.

## Phase 8: Simplify Controllers, Mapping, and Documentation

### Goal

Remove residual presentation-layer noise, complete documentation in the same work, and verify that
the codebase is measurably simpler without weakened boundaries.

### Scope

- Remove search filter-group helper methods used only to produce boolean log fields; keep concise
  page/sort/request logging without sensitive filter values.
- Remove unenforced Bean Validation annotations from response DTOs while preserving OpenAPI
  required/optional metadata.
- Consolidate duplicate statement-format response mapping into one mapper/factory.
- Remove redundant local JSON inclusion annotations when global serialization config already owns
  the behavior, after response serialization characterization confirms equivalence.
- Correct stale comments such as the PDF parser-type "future" wording and file-import duplicate
  rejection language that no longer matches advisory reupload behavior.
- Re-run a production call-site and null/branch inventory. Review every remaining concentrated file
  and document why high-branch areas are either business policy or external-boundary handling.
- Update `README.md`, `AGENTS.md`, `docs/api/README.md`, `docs/statement-import.md`,
  `docs/duplicate-detection.md`, `docs/saved-views.md`, `docs/domain-model.md`, and
  `docs/database-schema.md` only where the implemented internal contract or documented behavior is
  affected. Do not copy this plan wholesale into user documentation.

### Non-goals

- Do not chase a target line count, null-check count, cyclomatic score, or class size by weakening
  behavior or compressing readable code.
- Do not implement any requirement-level option from the discussion table.
- Do not remove OpenAPI endpoint documentation merely because it contributes controller lines.

### Required context

- Results and documentation changes from Phases 1-7.
- All repository docs named in Scope and `application.yml` serialization settings.
- `../service-common/docs/code-quality-standards.md`.

### Implementation notes

- Lead the final audit with concrete remaining branch rationales: external/untrusted input,
  authorization/ownership, optional API semantics, persistence state, or supported business policy.
- A remaining branch with no reachable state and no actionable caller response should be removed or
  explicitly deferred with evidence.
- Keep logging structured and free of file contents, transaction descriptions, tokens, and other
  sensitive data.
- Documentation changes are part of each preceding phase; this phase reconciles the complete set and
  must not be used to postpone known updates.

### Validation

Run the required full sequence one final time:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Then run focused inventories:

```bash
rg -n "Objects\.requireNonNull|== null|!= null" src/main/java
rg -n "canHandle|refreshCsvExtractors|extractEntities|getAllExtractors" src/main src/test
rg -n "ViewTransactionResponse|MembershipType|TransactionDescriptionMatchResult" src/main src/test
```

Review generated OpenAPI/serialized response tests and all documentation links.

### Completion criteria

- All behavior-preserving findings are resolved or retained with a concrete boundary/policy reason.
- No test-only production compatibility API, duplicate parser lifecycle, dynamic extractor cache, or
  repeated saved-view membership implementation remains.
- Authorization, ownership, soft delete, preview-token security, parser fixtures, duplicate policy,
  and HTTP response contracts remain green.
- The full build passes and the nearest affected documentation is current.
- Any approved requirement reduction is still isolated to a separate follow-on plan with explicit
  migration and compatibility work.
