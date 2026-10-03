# Releasing

Nothing has been published yet. This page lists what the build does, what the owner
must still do, and the steps for each release. The `io.axiom` group is provisional
until ownership of that namespace is validated; changing it is a deliberate decision
that is not covered here.

## Published modules

`axiom` (the starter, project `axiom-starter`), `axiom-core`, `axiom-server`,
`axiom-http`, `axiom-json`, `axiom-test`, `axiom-validation`, `axiom-validation-jakarta`
and `axiom-bom`. Examples, benchmarks and `integration-tests` are not published. A new library module that applies `axiom.java-library` is published
automatically; add it to `axiom-bom` as well.

## Owner actions before the first release

1. Fill the POM developer placeholders in `gradle.properties`:
   `axiom.pom.developerId`, `axiom.pom.developerName`, `axiom.pom.developerUrl`
   (currently `TODO-developer-id`, `TODO Developer Name` and a profile URL guess). The
   repository URL (`https://github.com/matrixjnr/axiom`) and the Apache-2.0 license
   come from the repository. A developer email is optional and not set.
2. Verify the namespace with Maven Central (or choose another group and rename it
   everywhere), and create the publishing account and a user token.
3. Create a signing key pair, publish the public key to a key server, and keep the
   private key out of the repository. Provide it to CI as secrets:
   `ORG_GRADLE_PROJECT_signingInMemoryKey` (armored private key),
   `ORG_GRADLE_PROJECT_signingInMemoryKeyId` (optional) and
   `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`.
4. Provide the publishing target and token from secrets as Gradle properties
   (`-Paxiom.publish.url=...`, `-Paxiom.publish.username=...`,
   `-Paxiom.publish.password=...`); property names with dots cannot be set through
   `ORG_GRADLE_PROJECT_` environment variables.
5. Decide the Central upload mechanism. The build currently produces signed artifacts
   in a Maven layout and can push to any Maven-style URL. The Central portal accepts a
   bundle upload (zip of the signed repository layout, with checksums) or a staging
   API; the release workflow does not do either yet.
6. GitHub Actions are pinned to full commit SHAs with the version as a trailing comment.
   To add or update one by hand, resolve the tag with
   `git ls-remote --tags https://github.com/<owner>/<repo> 'refs/tags/<tag>*'` (for an
   annotated tag use the `^{}` line, which is the commit), write `@<sha> # <tag>`, and
   check the SHA against the upstream release page. `.github/dependabot.yml` proposes weekly
   action updates and preserves the comment style.
7. Protect tags (`v*`) and limit who can push them; configure a protected environment
   with required reviewers for the publishing job when it exists.
8. Check that empty sources and Javadoc jars of the starter are accepted by Central.
   If they are not, add a package-less README resource or a placeholder class.

## Release checklist

1. Make sure `main` is green: `./gradlew clean check --rerun-tasks --no-build-cache`.
2. Update `CHANGELOG.md`: move the `Unreleased` entries under the new version and date.
3. Set `version=` in `gradle.properties` to the release version (no `-SNAPSHOT`) and
   update the install snippets in `README.md` (remove the "not yet published" note
   only after the artifacts are visible on Central).
4. Run `./gradlew compatibilityTest` locally, or let the workflow do it.
5. Open a PR with these changes and merge it after review.
6. Tag the merge commit `v<version>` (for example `v0.1.0`) and push the tag. The
   Release workflow checks that the tag equals the project version and is not a
   SNAPSHOT, runs `check` and `compatibilityTest`, and uploads the unsigned Maven
   repository as a workflow artifact.
7. Publish with signing: run the publish task with the signing secrets and
   `-Paxiom.release=true -Paxiom.publish.url=...`:
   `./gradlew publishAllPublicationsToRemoteRepository`. With that flag the build
   refuses to publish while placeholders remain or the key is missing.
8. Verify on Central that all nine modules, their `.module` files, signatures,
   sources and Javadoc jars are present, then run the Maven and Gradle snippets from
   the README against the released version.
9. Bump `version=` to the next `-SNAPSHOT` and add an empty `Unreleased` section.

## Snapshot policy

`main` carries a `-SNAPSHOT` version. Snapshots are not published by default; no
snapshot repository is configured. If one is added, publish only from `main` after
`check`, never from pull requests or forks. A release is never published from a
`-SNAPSHOT` version.

## SBOM plan

No SBOM is produced yet. Adding a CycloneDX Gradle plugin changes the trusted build
dependencies; it should be added deliberately, with its checksums recorded in
`gradle/verification-metadata.xml`. Plan: apply the plugin to the starter (which has
the full runtime classpath), emit a JSON SBOM for the `runtimeClasspath`, attach it to
the workflow artifacts and the release. Until then the dependency list in
`docs/build.md` and the verification metadata are the record of third-party content.

## Dependency verification

`gradle/verification-metadata.xml` is committed and enforced. After a dependency or
plugin version change, regenerate it with the command in `docs/build.md`, review the
diff, and commit it together with the version change. Checksums are
trust-on-first-use; consider enabling signature verification later.

## Netty and Jackson update policy

Netty (transport) and Jackson (codec) updates change runtime behavior, so they are
reviewed more closely than other dependency updates.

- Cadence: Dependabot proposes one grouped PR per family each week
  (`.github/dependabot.yml`). Take patch releases of the current minor line at least
  monthly and before every release; take a new minor line deliberately, in its own PR.
  A major version (for example Jackson 3) is a design decision, not a routine update.
- Security fixes: watch the GitHub security advisories of both projects. A fix for a
  vulnerability that is reachable through Axiom (HTTP parsing, compression, TLS, JSON
  parsing) is taken as soon as possible, outside the cadence, with a note in
  `CHANGELOG.md`. Record the triage result (affected, not affected, why) in the PR.
- Changelog review: read the release notes between the old and new version for changed
  defaults, deprecations, security fixes and behavior changes in the parts Axiom uses
  (HTTP/1.1 codec, buffer handling and leak detection; databind, streaming and
  `java.time`/`Optional` modules). Summarize anything relevant in the PR.
- Verification: run `./gradlew clean check --rerun-tasks --no-build-cache` (this includes
  the `integration-tests` module that runs the JSON contract against the real codec and
  listener), then `./gradlew compatibilityTest`, and rerun the JMH benchmarks in
  `benchmarks/` as described in `docs/benchmarks.md`. Compare against the previous
  version and state the result in the PR; a regression is a reason to hold the update.
- Metadata: the version change and the regenerated `gradle/verification-metadata.xml`
  land in the same commit (see Dependency verification). Dependabot cannot regenerate
  the file, so push the regeneration to the update PR's branch before merging.
- Keep the Netty family on one BOM version and the Jackson family on one BOM version;
  never bump a single artifact.

## Workflows

- `build.yml`: `./gradlew clean check` and the hello smoke run on JDK 21, Linux, for
  pushes to `main` and pull requests. Pull requests also run the `commits` job, which
  builds each commit of the PR (up to 20, see `docs/build.md`).
- `compatibility.yml`: weekly and on demand; runs `./gradlew compatibilityTest`
  (Gradle Kotlin, Gradle Groovy and Maven consumers).
- `release.yml`: on `v*` tags; skeleton described above.

All workflows request `contents: read` only and use no secrets. The publishing job,
which needs secrets, is not written yet.
