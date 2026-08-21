# Transaction Service Agent Instructions

## Repository Position

**Archetype:** service
**Scope:** transaction and budget management in the Budget Analyzer ecosystem
**Role:** owns financial transactions, saved transaction views, statement
formats, and file-based transaction imports

### Relationships

- Consume shared Java architecture and runtime libraries from
  `../service-common/`.
- Let `../orchestration/` own deployment, routing, infrastructure, and the
  full-stack local environment.
- Coordinate authorization semantics with `../permission-service/`; this
  service enforces the claims and permissions supplied through the trusted
  gateway path.
- Discover peer services with the commands in [Discovery](#discovery); do not
  maintain a peer inventory here.

### Boundaries

- Read this repository and the sibling documentation under
  `../service-common/`, `../orchestration/docs/`,
  `../permission-service/docs/`, and `../ai-session-handler/docs/` when the
  sources below require cross-repository context.
- Write source-controlled changes only within this repository. Do not modify
  sibling source, configuration, or documentation; report any required
  cross-repository change to the user.
- Treat the explicit `service-common` Maven Local recovery workflow as a build
  operation, not permission to edit that sibling repository.

## Discovery

Use direct repository search and reads for code exploration. Never use agent or
subagent tools for code exploration.

```bash
# Repository structure
find . -maxdepth 2 -type f -not -path './.git/*' -not -path './build/*' | sort

# Source and test files
rg --files src/main src/test | sort

# Peer services
find .. -maxdepth 1 -type d -name '*-service' -print | sort

# Controllers, routes, and method security
rg -n '@(Get|Post|Put|Patch|Delete)Mapping|@RequestMapping|@PreAuthorize' \
  src/main/java --glob '*.java'

# Domain, repositories, and migrations
find src/main/java/org/budgetanalyzer/transaction/domain \
  src/main/java/org/budgetanalyzer/transaction/repository \
  -type f -name '*.java' | sort
find src/main/resources/db/migration -maxdepth 1 -type f | sort

# Intentional and accidental service-to-API imports
rg -n '^import org\.budgetanalyzer\.transaction\.api\.' \
  src/main/java/org/budgetanalyzer/transaction/service \
  src/main/java/org/budgetanalyzer/transaction/repository --glob '*.java'

# Build tasks, dependencies, and runtime configuration
./gradlew tasks --quiet
rg -n 'dependencies|implementation|testImplementation|runtimeOnly' build.gradle.kts
sed -n '1,220p' src/main/resources/application.yml
```

## Sources of Truth

- **Purpose, prerequisites, setup, and local use:** Read
  [README.md](README.md) before changing prerequisites, setup assumptions,
  public usage, or local run behavior. Read
  [getting-started.md](../orchestration/docs/development/getting-started.md)
  before changing or debugging the full-stack Tilt workflow.
- **Runtime configuration:** Read
  [configuration.md](docs/configuration.md) and
  [application.yml](src/main/resources/application.yml) before changing or
  documenting server, database, logging, upload, or preview-token settings.
- **HTTP API and authorization:** Read
  [API documentation](docs/api/README.md) and inspect current controller
  annotations before adding, removing, or reshaping routes, request models,
  response models, filters, sorting, or permissions. Read the Permission
  Service [authorization model](../permission-service/docs/authorization-model.md)
  before changing permission semantics or cross-user scope.
- **Gateway, deployment, and exposure:** Read the orchestration
  [system overview](../orchestration/docs/architecture/system-overview.md) and
  [session-edge authorization pattern](../orchestration/docs/architecture/session-edge-authorization-pattern.md)
  before changing gateway, trusted-header, routing, or deployment assumptions.
  Read the active [port reference](../orchestration/docs/architecture/port-reference.md)
  when current service exposure or approved caller rules matter.
- **Domain and persistence:** Read
  [domain-model.md](docs/domain-model.md) before changing entities,
  relationships, packages, or service-owned concepts. Read
  [database-schema.md](docs/database-schema.md) and inspect the complete ordered
  migration directory before changing schema or persistence behavior.
- **Statement imports:** Read
  [statement-import.md](docs/statement-import.md) before changing formats,
  parsers, preview, batch import, or import troubleshooting. Read
  [duplicate-detection.md](docs/duplicate-detection.md) before changing
  transaction matching, file reupload tracking, preview tokens, or grouped
  batch semantics.
- **Shared Spring architecture:** Read
  [service-common/AGENTS.md](../service-common/AGENTS.md) before implementing a
  new feature that uses shared patterns. Read
  [spring-boot-conventions.md](../service-common/docs/spring-boot-conventions.md)
  when changing layers, controllers, services, repositories, entities, or API
  models.
- **Java quality:** Before writing or modifying any Java code, read
  [code-quality-standards.md](../service-common/docs/code-quality-standards.md).
  Do not skip this prerequisite.
- **Errors:** Read [error-handling.md](../service-common/docs/error-handling.md)
  when changing error flows or custom exceptions.
- **Tests:** Test pure logic with real objects and application behavior with
  real Spring beans. Do not mock or spy application-owned Spring beans. Before
  writing or modifying tests, read
  [testing-patterns.md](../service-common/docs/testing-patterns.md).
- **Build and dependencies:** Read `build.gradle.kts`, `settings.gradle.kts`,
  `gradle/libs.versions.toml`, and
  `gradle/wrapper/gradle-wrapper.properties` before changing the toolchain,
  plugins, dependencies, wrapper, formatting, tests, or coverage gates.
- **Dependency resolution:** Read
  [service-common artifact resolution](../orchestration/docs/development/service-common-artifact-resolution.md)
  when changing or debugging `service-common` resolution for local, CI, or
  release builds.

## Operating Rules

### Repository and Git Safety

- Never run git write operations such as `commit`, `push`, `checkout`, `reset`,
  branch manipulation, or history rewriting unless the user explicitly asks.
- Do not modify source-controlled files outside this repository. Surface
  required sibling-repository changes instead of making them.
- Check every plan or feature for documented prerequisites before
  implementation. If a required prerequisite is missing, stop and inform the
  user; do not invent a workaround.
- Do not bypass authentication, authorization, persistence, validation, or
  other enforced boundaries as a durable fix.
- Never log credentials, claims-header values, financial statement contents,
  import tokens, hashes, or other sensitive financial data.

### Java and Spring Architecture

- Follow the layered architecture: controllers own HTTP concerns, services own
  business rules and transaction boundaries, repositories own data access, and
  entities carry persistence state.
- Use dependency injection, declarative transactions, JPA, shared exception
  handling, Bean Validation, and structured SLF4J logging according to the
  shared owner documents.
- Choose the simplest implementation that correctly handles realistic inputs,
  states, and failure modes. Do not trade away security, data integrity, or
  required behavior for brevity.
- Put request shape and syntax validation in request models and controllers.
  Put business invariants, ownership, persistence state, and cross-entity rules
  in services.
- Do not duplicate API validation in a service when every caller passes through
  the validated API contract. Validate again only when another caller can
  bypass that boundary or when the service owns the rule.
- Do not add guards, fallbacks, custom exception paths, abstractions, or
  extension points for states that enforced boundaries make impossible.
- Handle plausible failures explicitly at external or asynchronous boundaries.
  Before adding a defensive branch, identify how the state can arise and what
  the caller or system can usefully do in response; omit the branch if neither
  is concrete.

### Authorization and Ownership

- Protect every application controller endpoint with fine-grained
  `@PreAuthorize` checks. Preserve only the public infrastructure surfaces
  documented by the active API and shared security configuration.
- Treat `X-User-Id`, `X-Permissions`, and `X-Roles` as trusted only after the
  gateway and `ClaimsHeaderSecurityConfig` path has established the Spring
  Security context. Obtain actor identity from that context, not from request
  bodies or application-level parsing of untrusted headers.
- Keep ordinary transaction operations scoped to the authenticated owner.
  Require the documented `:any` permission for cross-user behavior, and do not
  make an owner filter effective on self-scoped endpoints.
- Use `ClaimsHeaderTestBuilder` for per-request authentication in controller
  tests, following the shared testing patterns.

### Transaction and Import Invariants

- Soft-delete transactions. Never hard-delete them, and keep normal queries
  restricted to active rows.
- Keep CSV import configuration-driven. A normal new CSV bank format should be
  represented by statement-format and parser-revision data rather than new Java
  parsing code. Use dedicated handlers only for formats, such as specialized
  PDFs, that require code.
- Keep the public import identity at the statement-format level; parser
  revisions remain internal implementation and provenance detail. Preserve
  ordered, atomic preview-to-batch behavior described by the import owner docs.
- Test parser or format changes with sanitized real bank exports for every
  affected format. Do not commit sensitive statement samples.
- Build advanced transaction search with JPA Specifications. Preserve owner
  scoping and the documented separation between self-scoped and cross-user
  search.

### Layering Exception

- Treat the existing `TransactionFilter` service/repository import from
  `api.request` as the only intentional `service -> api` crossing. It carries
  Spring MVC query-binding annotations and maps directly to transaction
  criteria. Do not create another crossing; introduce a service-layer model
  instead.
- Keep API-side HTTP-to-internal conversion helpers controller-owned. Call them
  only at the controller boundary; never call them from `service/` or
  `repository/`.

## Development Workflow

1. Read the relevant source-of-truth documents and check their prerequisites
   before implementation.
2. Confirm the required toolchain, database or container runtime, credentials,
   and external services are available for the requested work.
3. Inspect current source, tests, migrations, configuration, and build files
   with the discovery commands above.
4. Implement the smallest coherent change without weakening security, data
   integrity, validation, or test coverage.
5. Update the nearest owner documentation in the same work.
6. Run every validation gate appropriate to the changed files. If a required
   verifier is unavailable, report it instead of claiming success.

Use `./gradlew bootRun` for the service-only local entry point after satisfying
the prerequisites in `README.md`. Use the orchestration getting-started guide
for the supported full-stack path.

### AI Session Handler Plans

When creating an implementation or execution plan for AI Session Handler, read
and follow [plan-format.md](../ai-session-handler/docs/plan-format.md). Use its
canonical template, replace every placeholder, and retain numbered
`## Phase N: Title` headings.

Run a plan from this repository root with:

```bash
ai-session-handler run \
  --plan docs/plans/PLAN.md \
  --max-phases 999 \
  --quiet \
  --agent-cmd "../ai-session-handler/.venv/bin/ai-session-handler-codex-high --model MODEL"
```

Remove `--model MODEL` from the quoted agent command to use the wrapper's
configured or default model.

### Service-Common Recovery

If `service-common` cannot resolve from local artifacts or GitHub Packages,
immediately publish the sibling project to Maven Local before retrying this
service:

```bash
(cd ../service-common && ./gradlew clean build publishToMavenLocal)
./gradlew clean build
```

Do not edit `service-common` to work around resolution failures. Use the
artifact-resolution owner document for CI or release failures.

## Validation

Before completing Java, Gradle, configuration, or migration changes, run these
commands in sequence:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

- Inspect the full build output and fix Checkstyle warnings even if Gradle exits
  successfully.
- Use focused tests for iteration, but do not substitute them for the required
  full build.
- For configuration changes, run the affected configuration or startup tests
  in addition to the full build and confirm `docs/configuration.md` matches the
  runtime configuration.
- For migration changes, inspect the complete ordered migration history, run
  the affected repository or service integration tests, and update the schema
  owner documentation in addition to the full build.
- For import changes, run the affected automated tests and sanitized real-bank
  sample checks in addition to the full build.
- For documentation-only changes, run
  `git diff --check -- AGENTS.md README.md docs`, verify every changed local
  link target and anchor, and run or syntax-check every changed command. Do not
  run Gradle solely for Markdown changes.
- Never disable, weaken, or delete an existing test to make a change pass. If
  an unrelated test is already failing, stop and report it.
- If any required verifier cannot run because a tool, service, credential,
  sample, or container runtime is unavailable, state exactly what was not
  verified and why. Do not represent the work as fully verified.

## Documentation Maintenance

- Keep documentation current in the same work as configuration or code
  changes. Do not leave required documentation updates as follow-up work.
- Update `AGENTS.md` when agent instructions, guardrails, discovery commands,
  authority boundaries, workflows, or source-of-truth ownership changes.
  Before creating, reviewing, or substantially revising it, read and apply the
  [AGENTS.md checkstyle](../orchestration/docs/agents-md-checkstyle.md).
- Update `README.md` when setup, usage, repository purpose, or human onboarding
  changes.
- Update `docs/` when architecture, configuration, APIs, behavior, operations,
  or design rationale changes.
- Do not update archived documents unless the user explicitly requests it.
- Keep detailed recurring topics in one active owner document and link to it
  instead of duplicating it here.

## Honest Discourse

- Say directly when an idea or assumption is wrong.
- Distinguish novel work from conclusions that are obvious in retrospect.
- Push back on vague claims and request concrete constraints when they are
  necessary to proceed.
- Skip praise, preambles, and unnecessary caveats; lead with the evidence and
  outcome.
