# Contributing

Use JDK 21 and the committed Gradle wrapper. Run `./gradlew clean check` before
opening a pull request. Keep changes focused and reference relevant architecture
decisions. Add tests beside the module being changed. Keep implementation types
out of public signatures and use `api` dependencies only when consumers need them.

Shared build configuration belongs in `build-logic`; dependency versions belong
in `gradle/libs.versions.toml`. Repositories are controlled by settings.
Do not commit generated output or IDE settings. Do not enable build scan uploads
by default. Runtime features need failure-path and resource lifecycle tests.

Use self-contained commit messages and PR titles without specification IDs. Explain
the problem, behavior, design tradeoffs, and validation in the PR description. Do
not add co-author trailers. See AGENTS.md for repository-specific agent guidance.
