# Dependency Automation

The workspace-wide operating policy, production operation, cost boundary, and
failure triage are owned by
[orchestration's dependency automation guide](../../orchestration/docs/dependency-automation.md).
This document records only the `transaction-service` integration and review
checks.

## Update discovery

`renovate.json` extends the shared Budget Analyzer production preset from the
orchestration repository. Renovate's native Gradle, Gradle Wrapper, Dockerfile,
and GitHub Actions managers discover this repository's version catalog, build
script, wrapper distribution, base images, and workflow actions. The Gradle
catalog extraction includes the declared `serviceCommon` version used by
`spring-platform` and `service-web`.

`service-common` is hosted in GitHub Packages. The Mend Renovate Community App
requires its supported encrypted Maven credentials to look up those private
coordinates; never put a package token in this repository or the shared preset.
The app must keep direct, maintained-line, and later-major proposals visible
where each datasource supports them.

Renovate may propose only `serviceCommon` coordinates already available from
the configured package source. Publishing internal libraries and coordinating
cross-repository consumer updates remain part of the existing release process.
Renovate proposes changes only to direct declarations. Versions inherited from
the Spring Boot and `spring-platform` BOMs remain represented by the resolved
dependency graph described below.

## Resolved dependency graph

`.github/workflows/dependency-submission.yml` generates and submits the Gradle
dependency graph from trusted `main` pushes, weekly runs, and `main`
dispatches. It resolves all projects and all resolvable configurations so
application, runtime, build, and test dependency trees are included. Do not add
configuration filters without proving equivalent coverage.

Remote resolution uses `SERVICE_COMMON_PACKAGES_USERNAME` and
`SERVICE_COMMON_PACKAGES_READ_TOKEN`, exposed to Gradle as `GITHUB_ACTOR` and
`GITHUB_TOKEN`. These package-read credentials are distinct from
`${{ github.token }}`, which the action uses to submit the graph with the
job's only elevated permission, `contents: write`. Gradle resolution is
authoritative for both release and timestamped snapshot artifacts; do not add
manual artifact URL probes that duplicate Gradle's Maven metadata handling.
The graph snapshot is submitted directly and is not retained as an artifact or
published as a Build Scan.

The workflow must fail when package credentials are missing, either pinned
`service-common` artifact cannot be resolved, graph generation is incomplete,
or GitHub rejects submission. Do not substitute Maven Local, omit an internal
dependency, filter failed configurations, or treat graph-generation failure as
a clean security result.

## Build validation and artifacts

`.github/workflows/build.yml` runs the repository build for `main` pushes,
pull requests targeting `main`, and manual dispatches. It uses the same
package-read credentials as dependency submission to resolve the pinned
`service-common` artifacts.

Regular CI does not upload the application JAR. When the Gradle build fails, the
workflow uploads only JUnit XML test results and retains them for one day.
Successful runs retain no build artifact or test-results artifact.

## Bot pull request checks

Every Renovate pull request remains non-automerged. Review the resolved
dependency diff, release notes, Java 25 and Spring compatibility, and whether
the proposed direct dependency actually remediates any inherited alert. Major
updates remain visible in the Dependency Dashboard and require approval.

Run the required repository validation in order:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

Bot pull requests use the existing `build.yml` pull-request workflow and, when
they change the Dockerfile, Gradle wrapper, or build scripts, the
[image build check](#image-build-check).
Forked or otherwise untrusted pull requests do not receive package-read
secrets, so a failure to resolve `service-common` there is an unavailable-secret
condition, not evidence that the dependency is absent. Do not add
`pull_request_target` or expose package credentials to untrusted dependency
branches to bypass that boundary.

### Image build check

`.github/workflows/image-build-check.yml` runs on pull requests targeting `main`
that change `Dockerfile`, `gradlew`, anything under `gradle/wrapper/`,
`build.gradle.kts`, `settings.gradle.kts`, or the workflow itself, and on
manual dispatch. Application source is excluded because `build.yml` already
compiles and tests it on every pull request. The version catalog
`gradle/libs.versions.toml` is also excluded: `build.yml` compiles, tests and
checks catalog-only dependency and tool updates, and the image check would only
rebuild the same JAR under slow emulation. `build.gradle.kts` stays in the
filter because it owns the GitHub Packages repository and credential wiring
that the BuildKit secrets exercise inside the builder.

A catalog-only pull request therefore does not prove in-container dependency
resolution. When a catalog change warrants that proof, such as a
`service-common` or Spring Boot major update, run the workflow manually with
`workflow_dispatch` on the pull-request branch.

The check builds the same `./Dockerfile` and context as `publish-release.yml`
for `linux/arm64` under QEMU, with `push: false` and `outputs: type=cacheonly`.
A pass proves the release image still builds for `linux/arm64`, including that
every pinned base image provides an arm64 manifest and that dependency
resolution and `bootJar` succeed inside the builder. It does not log in to a
registry, tag, push, or cache the image, and it does not prove runtime behavior
or scan the image for vulnerabilities. Emulated builds are slow; the job has a
60-minute timeout.

The builder receives only the `github_actor` and `github_token` BuildKit
secrets that the release workflow passes, sourced from
`SERVICE_COMMON_PACKAGES_USERNAME` and `SERVICE_COMMON_PACKAGES_READ_TOKEN`.
The package-secret boundary above applies unchanged: fork pull requests without
secrets fail to resolve `service-common`, and that failure is an
unavailable-secret condition.

Do not make `Image Build Check` a required status check while it is
path-filtered. Pull requests that touch no filtered path never start it, so a
required check would leave them waiting indefinitely.

## Production verification

After changes reach `main`, confirm the normal Mend cycle resolves the shared
preset and private Maven coordinates, targets `main`, applies the expected
label and concurrency limits, and leaves automerge disabled. A clean Dependency
Dashboard or hosted log is sufficient when no update is available.

Confirm one successful normal Build run has no application JAR or test-results
artifact. Confirm one Dependency Submission run resolves the complete
application, runtime, build, and test graph and is accepted by GitHub.
Dependency graph and Dependabot alerts must remain enabled, while overlapping
Dependabot version-update and security-update pull request creation remains
disabled.
