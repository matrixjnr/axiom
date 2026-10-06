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
5. Run `./gradlew clean check` and the relevant examples. For consumer-facing changes, also run
   the published-artifact consumers described in [releasing](releasing.md).

Missing baselines fail the check, including for a newly published module. A reviewed baseline
can be regenerated locally using the configured Java 21 toolchain; no extra runtime or library
dependency is introduced.

## Limits of this gate

This is an exact signature review gate, not a complete source/binary compatibility analyzer.
It does not compare inherited members supplied by dependencies, annotations/default annotation
values, sealed-class permitted subclasses, or record-component metadata/order. It does not
prove runtime behavior, wire-format compatibility, or compatibility with an already released
artifact. Those require focused tests and a fuller compatibility analyzer before a stable
release, tracked in [#174](https://github.com/matrixjnr/axiom/issues/174). Updating a baseline is
an explicit review decision, not evidence that a change is safe.
