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
6. Pin GitHub Actions to full commit SHAs. Workflows use tags (`actions/checkout@v4`,
   `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`,
   `actions/upload-artifact@v4`). Full SHAs could not be verified from the build
   environment and were not guessed. Resolve each tag to its commit in the upstream
   repository, replace the tag, and keep the tag in a trailing comment.
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

## Workflows

- `build.yml`: `./gradlew clean check` and the hello smoke run on JDK 21, Linux, for
  pushes to `main` and pull requests.
- `compatibility.yml`: weekly and on demand; runs `./gradlew compatibilityTest`
  (Gradle Kotlin, Gradle Groovy and Maven consumers).
- `release.yml`: on `v*` tags; skeleton described above.

All workflows request `contents: read` only and use no secrets. The publishing job,
which needs secrets, is not written yet.
