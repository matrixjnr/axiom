---
title: API compatibility
parent: Operations
nav_order: 7
---

# Reviewing API changes

Each published Java module has a reviewed signature file in [`api/`](../api/). The publication
convention registers `apiCheck` as part of the module's `check`, so the existing CI checks and
per-commit checks reject an API change without its matching baseline update. The BOM has no
Java classes; modules with no exported declarations have an explicit empty baseline.

## What is checked

JDK 21 `javap` reads the compiled classes without initializing them. The snapshot includes
public and protected type/member declarations, JVM descriptors, generic signatures, superclass
and interface declarations, declared exceptions, constant values, and compiler-generated bridge
methods. The baseline is sorted so source-file locations, declaration order, and method-body
changes do not create noise. LF and CRLF baseline files compare equally.

Nested-type access and static flags are read from the class file's
[InnerClasses attribute](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.7.6),
because `javap` displays protected nested classes as public. No application class is loaded.

Packages containing an `internal` segment are implementation details and excluded, matching
the Javadoc policy. Private/package-private types and their nested types are excluded too.
Public provider SPIs outside those packages are included even when documented as experimental;
a baseline does not promise that an experimental contract is stable.

## Making a change

1. Run `./gradlew apiCheck` to compare all published Java modules, or
   `./gradlew :axiom-core:apiCheck` for one module. `./gradlew check` includes the same checks.
2. Inspect the generated `MODULE/build/api/current.api` file and the proposed source change.
   A removed method, a narrower return descriptor, reduced visibility or a new abstract method
   can break existing consumers. Additions also require review because of overload resolution
   and interface implementation concerns.
3. Prefer preserving existing constructors/methods and adding a compatible overload. Before
   1.0, an intentional break needs a migration note in the guide and changelog, consumer tests,
   and an explicit explanation in the PR. After a stable release, review the versioning policy
   before approving a break.
4. Run `./gradlew :axiom-core:apiUpdate` for each intentionally changed module, review the
   resulting `api/*.api` diff, and commit it with the implementation. The aggregate
   `./gradlew apiUpdate` updates every published module. These generated baseline files are
   deliberately checked in; `apiCheck` never rewrites them.
   If `apiUpdate` and `apiCheck` are explicitly requested in the same build, the update
   finishes first. Running `check` alone never schedules an update.
5. Run `./gradlew clean check` and the relevant examples. For consumer-facing changes, also run
   the published-artifact consumers described in [releasing](releasing.md).

Missing baselines fail the check, including for a newly published module. A reviewed baseline
can be regenerated locally using the configured Java 21 toolchain; no extra runtime or library
dependency is introduced.

## Versioned artifact comparison

The signature files remain the mandatory review gate. `apiArtifactCheck` adds analysis against
separately retained JARs using [japicmp](https://siom79.github.io/japicmp/CliTool.html), with
different dependency classpaths for the baseline and candidate. Build-only ASM checks supplement
the analyzer with annotation/default values, type-use annotations, sealed permitted subclasses,
and ordered record components. Metadata changes require review even when the analyzer finds no
binary or source incompatibility. Reports show the old and new metadata separately.

Capture a baseline from an approved checkout, using its immutable commit SHA as the identity:

```text
./gradlew clean check captureApiArtifact "-PapiArtifactVersion=<baseline-commit-sha>"
```

Copy `build/api-artifacts/` to retained storage outside **every project build directory** before
switching checkouts. Keep `inventory.properties`, all module directories and dependency files
together. The inventory records the published module set and checksums each module manifest;
each manifest checksums the module JAR and its exact dependency closure. Comparison rejects a
missing bundle, changed checksum, wrong module/version, removed module, unsafe path or missing
dependency. Never point the retained baseline at current generated output. Configuration rejects
overlapping paths before `clean` can run.

From the candidate checkout, run:

```text
./gradlew clean check "-PapiBaselineDir=/absolute/path/to/retained/baseline" "-PapiBaselineVersion=<baseline-commit-sha>" "-PapiArtifactVersion=<candidate-commit-sha>" --continue
```

Supplying `apiBaselineDir` makes artifact comparison part of `check`. To run it directly, use
`apiArtifactCheck` with those same properties. Each module writes
`build/reports/api-artifacts/comparison.txt`, separating binary incompatibilities, source
incompatibilities and metadata requiring review. Missing dependencies are errors; they are never
ignored. The candidate is captured separately and comparison never updates retained bundles.
The code reads class files without initializing application classes.

Before 1.0, intentional breaks need migration notes and an explicit baseline/version decision.
For stable releases, preserve existing consumers or choose a major release and review its
baseline deliberately. Experimental SPIs are included; their status does not silently suppress
findings. New published modules also need a deliberate baseline decision. Tests of runtime and
wire behavior remain necessary.

## Retained bundles in CI

The **API artifacts** workflow verifies and captures candidates on pull requests and pushes to
`main`. Artifacts are named `api-artifacts-<commit-sha>` and retained for 90 days. Pull-request
candidates are not accepted as baselines. To compare a candidate, dispatch the workflow with the
numeric run ID of a successful API artifacts **push on main**. The workflow verifies the run's
repository, event, branch, workflow path and commit identity, downloads that exact bundle, then
runs the comparison and uploads its reports. The first eligible baseline becomes available after
this workflow merges and completes on `main`.

Approved release bundles must be retained separately before Actions retention expires. Local
comparison accepts the same portable bundle format, so the check is independent of Actions
storage once downloaded. Checksums detect corruption; the owner must choose a trusted baseline.

## Limits of the signature gate

This is an exact signature review gate, not a complete source/binary compatibility analyzer.
It does not compare inherited members supplied by dependencies, annotations/default annotation
values, sealed-class permitted subclasses, or record-component metadata/order. It does not
prove runtime behavior, wire-format compatibility, or compatibility with an already released
artifact. Use the versioned artifact comparison above for those class-file checks. Neither
signature checks nor artifact analysis prove runtime or wire-format behavior. Updating a baseline
is an explicit review decision, not evidence that a change is safe.
