# Contributing

Use JDK 21 and the committed Gradle wrapper. Run `./gradlew clean check` before
opening a pull request. Keep changes focused and reference relevant architecture
decisions. Add tests beside the module being changed. Keep implementation types
out of public signatures and use `api` dependencies only when consumers need them.

Tests that open a real socket or start a live listener are tagged `@Tag("integration")`
and run in the module's `integrationTest` task (the module applies the
`axiom.integration-test` convention); all other tests run in `test`. `./gradlew unitTest`
and `./gradlew integrationTest` run either half for every module; `check` runs both. See
`docs/build.md`.
`./gradlew coverageReport` writes per-module and aggregated JaCoCo reports; coverage
is reported, not yet enforced.

CI splits the build into the jobs `build` (compile, Javadoc, architecture and
publication checks), `unit` (each module's `test`), `integration` (`integrationTest`),
`quality` (coverage report and the hello smoke run) and the aggregate `check`, which
branch protection requires. See `docs/build.md` for what each job runs.

Every commit of a pull request must pass `./gradlew check` on its own; CI builds each
commit (see `docs/build.md`). Add the checksums in `gradle/verification-metadata.xml`
in the same commit as the dependency or plugin change that needs them, never in a later
commit. Commits that do not build break bisecting.

CI builds every commit of a pull request, oldest first, and reports all failing commits.
Building a commit costs a full `check` (several minutes), so a series is bounded: up to
20 commits are all built; in a longer series only the newest 20 commits that touch build
files are built, and the others are listed in a warning of the `commits` job. That covers
the commits that can break a build while the final tree passes; keep series short, or
split them into several pull requests, if every commit must be verified. Dependency
update pull requests (head branch `dependabot/...`) build only the tip commit, because the
bot's first commit cannot contain the checksums; push the regenerated verification
metadata on top (see `docs/releasing.md`) and squash-merge.

Shared build configuration belongs in `build-logic`; dependency versions belong
in `gradle/libs.versions.toml`. Repositories are controlled by settings.
Do not commit generated output or IDE settings. Do not enable build scan uploads
by default. Runtime features need failure-path and resource lifecycle tests.

Use self-contained commit messages and PR titles without specification IDs. Explain
the problem, behavior, design tradeoffs, and validation in the PR description. Do
not add co-author trailers. See AGENTS.md for repository-specific agent guidance.
