# CSV Date Validation and AI Session Artifact Review Fixes

**Status:** Draft
**Service:** transaction-service

Resolve the two review findings on the `simplify-defensive-code` branch: remove generated AI
Session Handler files from the proposed repository tree, and reject syntactically invalid CSV date
patterns before `POST /v1/statement-formats` persists either a statement format or its initial
parser revision. Preserve on-demand extractor construction and the existing ignored runtime-output
behavior.

## Phase 1: Remove Generated AI Session Handler Artifacts

### Goal

Remove every `.ai-session-handler` file added by the branch while retaining the ignore rule and any
new ignored runtime files created by the plan's own AI Session Handler run.

### Scope

- Identify files added under `.ai-session-handler` relative to `main` with a read-only diff.
- Delete the branch-added handler state JSON, generated prompts, and generated transcripts from the
  working tree.
- Keep the `.ai-session-handler/` entry in `.gitignore` so subsequent runs remain untracked.
- Verify that the proposed tree has no `.ai-session-handler` additions relative to `main`.

### Non-goals

- Do not remove the `.ai-session-handler/` ignore rule.
- Do not delete ignored files created by the currently running plan when they are not part of the
  branch diff.
- Do not rewrite commit history or run `git add`, `git rm`, `git commit`, `git checkout`, `git
  reset`, or any other Git write operation.
- Do not change AI Session Handler configuration outside the generated files identified by the
  review.

### Required context

- Repository `AGENTS.md`, especially the no-Git-write rule and documentation discipline.
- `.gitignore` and its existing `.ai-session-handler/` rule.
- The branch-relative artifact inventory from:

  ```bash
  git diff --name-status main -- .ai-session-handler
  git diff --numstat main -- .ai-session-handler
  ```

### Implementation notes

- Generate the exact deletion list with:

  ```bash
  git diff --name-only --diff-filter=A main -- .ai-session-handler
  ```

- Delete only the paths returned by that command, using `apply_patch`. The expected set is the
  branch-added run-state JSON plus the eight generated prompt files and eight generated transcript
  files from the reviewed session.
- Do not delete the directory wholesale: the wrapper executing this plan may have created new,
  ignored state, prompt, or transcript files with different names.
- The files are already ignored but were force-added earlier. Deleting their tracked branch copies
  and retaining the ignore rule prevents equivalent files from entering later changes.
- No user-facing documentation change is needed because this phase removes ephemeral development
  output without changing service setup, behavior, or configuration.

### Validation

Run these read-only checks after the deletions:

```bash
git diff --name-status main -- .ai-session-handler
git diff --numstat main -- .ai-session-handler
git check-ignore -v .ai-session-handler/transcripts/verification-only.txt
git status --short .ai-session-handler .gitignore
```

The first two commands must produce no output. `git check-ignore` must identify the repository's
`.ai-session-handler/` rule. `git status` may show deletions relative to the current branch commit
and may omit newly generated ignored files; it must not show a removal of the ignore rule.

### Completion criteria

- No file under `.ai-session-handler` remains in the proposed tree relative to `main`.
- The branch-added state, prompt, and transcript files are deleted without touching unrelated
  current-run output.
- `.gitignore` still ignores the complete `.ai-session-handler/` directory.
- No Git write operation or history rewrite was performed.

## Phase 2: Validate CSV Date Patterns Before Persistence

### Goal

Make direct CSV statement-format creation reject an invalid date pattern such as
`not-a-pattern` before saving a `StatementFormat` or `ParserRevision`, while continuing to accept
valid patterns supported by the configurable CSV extractor.

### Scope

- Add date-pattern syntax validation to `StatementFormatService.validateCreateCommand(...)`.
- Return a field-addressable `dateFormat` validation error under the existing
  `STATEMENT_FORMAT_VALIDATION_FAILED` business error.
- Add a regression test in `StatementFormatServiceTest` that proves both repositories remain
  untouched for an invalid pattern.
- Update `docs/api/README.md` and `docs/statement-import.md` with the direct-create date-pattern
  requirement and rejection behavior.
- Run the repository's required formatting and full-build sequence.

### Non-goals

- Do not restore `refreshCsvExtractors()`, dynamic extractor caches, or a dependency from
  `StatementFormatService` to `StatementExtractorRegistry`.
- Do not construct an extractor during statement-format creation.
- Do not restrict direct creation to the CSV wizard's curated `SUPPORTED_DATE_FORMATS` list; direct
  creation only needs to reject patterns that the Java formatter cannot construct.
- Do not change parsing semantics, resolver style, CSV header rules, parser-revision selection, or
  handling of already persisted legacy revisions.
- Do not add a new validator abstraction for this single syntax check unless another current
  production call site demonstrably needs the same service-owned validation.

### Required context

- Repository `AGENTS.md`.
- `../service-common/docs/code-quality-standards.md` before modifying Java.
- `../service-common/docs/testing-patterns.md` and
  `../service-common/docs/error-handling.md` before modifying tests or error behavior.
- `StatementFormatService.createFormat(...)`, `validateCreateCommand(...)`, and
  `serializeCsvConfig(...)`.
- `ConfigurableCsvStatementExtractor.buildDateFormatter(...)` for the formatter syntax currently
  accepted at extractor construction.
- `CsvStatementFormatWizardService.validateDateFormat(...)` for field-error wording only; its
  supported-pattern allowlist must not be applied to the direct-create endpoint.
- Existing `StatementFormatServiceTest.CreateFormat` coverage and the direct-create documentation
  in `docs/api/README.md` and `docs/statement-import.md`.

### Implementation notes

- Keep validation in `StatementFormatService` because `StatementFormatCommand` is also a service
  boundary and parser configuration validity is the invariant that must hold before persistence.
- After the existing required-field check, skip formatter construction when `dateFormat` is blank
  so one missing value produces the existing required error rather than duplicate errors.
- For a non-blank value, construct a `DateTimeFormatter` with the same pattern and `Locale.ROOT`
  used by `ConfigurableCsvStatementExtractor`. Catch `IllegalArgumentException` and append a
  `FieldError` for `dateFormat` with a concise message such as `Date format pattern is invalid.`
- Let the existing aggregate error path throw `BusinessException` with
  `BudgetAnalyzerError.STATEMENT_FORMAT_VALIDATION_FAILED`; validation must finish before
  `mapToEntity(...)`, `statementFormatRepository.save(...)`, or
  `parserRevisionRepository.save(...)` can run.
- In `StatementFormatServiceTest`, create an otherwise valid CSV command whose date pattern is
  `not-a-pattern`. Assert the exception code, the `dateFormat` field error and rejected value, and
  verify that neither repository receives a `save(...)` call. Keep the existing successful create
  test as positive coverage for a valid pattern.
- Document that direct creation requires syntactically valid Java date-time pattern syntax which
  also matches the CSV values, and that an invalid pattern is rejected without creating the format
  or revision.

### Validation

Run the required repository-wide sequence:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Then run final change checks:

```bash
git diff --check
git diff --name-status main -- .ai-session-handler
git diff --numstat main -- .ai-session-handler
git check-ignore -v .ai-session-handler/transcripts/verification-only.txt
```

The build must include the new invalid-pattern regression test. Both `.ai-session-handler` diff
commands must remain empty even though this phase's wrapper may create additional ignored local
runtime files.

### Completion criteria

- A direct create command with `dateFormat = "not-a-pattern"` fails with
  `STATEMENT_FORMAT_VALIDATION_FAILED` and a `dateFormat` field error.
- Invalid syntax is rejected before either the statement format or initial parser revision is
  saved.
- Existing valid CSV date patterns still create a format and parser revision successfully.
- On-demand extractor construction remains intact and no cache-refresh coupling is reintroduced.
- The direct-create API and import documentation describe the enforced date-pattern contract.
- No generated AI Session Handler state, prompt, or transcript file appears in the proposed tree.
- `./gradlew clean build` and `git diff --check` pass.
