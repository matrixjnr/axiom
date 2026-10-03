plugins {
    base
    id("axiom.publication-coverage")
    `jacoco-report-aggregation`
}

val modules = subprojects.filter { it.buildFile.isFile }
tasks.named("check") { dependsOn(modules.map { "${it.path}:check" }) }
// The convention checks themselves (publication coverage, module boundaries) have unit tests in
// build-logic; an included build's own check is not run by the root check unless wired here.
tasks.named("check") { dependsOn(gradle.includedBuild("build-logic").task(":check")) }
tasks.named("assemble") { dependsOn(modules.map { "${it.path}:assemble" }) }

// README examples: a code block marked `<!-- snippet: path -->` must equal the compiled source it
// quotes (see docs/build.md), so the examples cannot go stale silently.
val checkReadmeSnippets = tasks.register<CheckReadmeSnippets>("checkReadmeSnippets") {
    group = "verification"
    description = "Fails when a README code block differs from the compiled example source it quotes."
    readme = layout.projectDirectory.file("README.md")
    root = layout.projectDirectory
    sources.from(fileTree("examples/readme/src") { include("**/*.java") })
    sources.from(fileTree("examples/openapi/src") { include("**/*.java") })
}
tasks.named("check") { dependsOn(checkReadmeSnippets) }

// The same guarantee for the OpenAPI guide, whose examples are compiled and tested in examples/openapi.
val checkOpenApiGuideSnippets = tasks.register<CheckReadmeSnippets>("checkOpenApiGuideSnippets") {
    group = "verification"
    description = "Fails when a code block of docs/openapi.md differs from the compiled example source it quotes."
    readme = layout.projectDirectory.file("docs/openapi.md")
    root = layout.projectDirectory
    sources.from(fileTree("examples/openapi/src") { include("**/*.java") })
}
tasks.named("check") { dependsOn(checkOpenApiGuideSnippets) }

// Test split (see docs/build.md): `unitTest` runs every module's `test` (no real sockets),
// `integrationTest` every module's `integrationTest` (tests tagged integration, plus the whole
// integration-tests module). Both are part of each module's check.
fun testTasks(name: String) = provider {
    subprojects.filter { name in it.tasks.names }.map { "${it.path}:$name" }
}
tasks.register("unitTest") {
    group = "verification"
    description = "Runs the unit tests (each module's test task) of all modules."
    dependsOn(testTasks("test"))
}
tasks.register("integrationTest") {
    group = "verification"
    description = "Runs the integration tests (each module's integrationTest task) of all modules."
    dependsOn(testTasks("integrationTest"))
}

// The CI unit-test matrix is generated from this task, so a module with unit tests cannot be left
// out of CI: `./gradlew -q unitTestMatrix` prints {"include":[{"module":..,"path":..},..]} for every
// module whose `test` task is enabled and has at least one test class not tagged integration.
tasks.register("unitTestMatrix") {
    group = "verification"
    description = "Prints the GitHub Actions matrix of modules that have unit tests, as JSON."
    val matrix = objects.property<String>()
    // Evaluated after the modules are configured; the task output is only the printed JSON.
    matrix.set(provider {
        subprojects
            .filter { "test" in it.tasks.names && it.tasks.named("test").get().enabled }
            .filter { module ->
                module.fileTree("src/test/java") { include("**/*.java") }.files.map { it.readText() }
                    .any { it.contains("@Test") && !it.contains("Tag(\"integration\")") }
            }
            .joinToString(",", prefix = "{\"include\":[", postfix = "]}") { module ->
                val path = module.path.removePrefix(":")
                "{\"module\":\"${path.replace(':', '-')}\",\"path\":\":$path\",\"dir\":\"${path.replace(':', '/')}\"}"
            }
    })
    doLast { println(matrix.get()) }
}

// Coverage (see docs/build.md). The aggregated report covers the library modules and the
// integration-tests module, from the execution data of both test tasks. The BOM has no code;
// examples and benchmarks are not library code. No class is excluded.
jacoco { toolVersion = libs.versions.jacoco.get() }
val coverageModules = listOf("axiom-core", "axiom-server", "axiom-http", "axiom-json", "axiom-test",
    "axiom-starter", "axiom-validation", "axiom-validation-jakarta", "axiom-security", "axiom-security-jwt",
    "axiom-metrics", "axiom-openapi", "integration-tests")
dependencies { coverageModules.forEach { jacocoAggregation(project(":$it")) } }
reporting {
    reports {
        register<JacocoCoverageReport>("testCodeCoverageReport") { testSuiteName = "test" }
        register<JacocoCoverageReport>("integrationTestCodeCoverageReport") { testSuiteName = "integrationTest" }
    }
}
val unitCoverage = tasks.named<JacocoReport>("testCodeCoverageReport")
val integrationCoverage = tasks.named<JacocoReport>("integrationTestCodeCoverageReport")
tasks.register<JacocoReport>("coverageReport") {
    group = "verification"
    description = "Runs the tests and writes the per-module and the aggregated JaCoCo reports " +
        "(build/reports/jacoco/coverageReport)."
    executionData.from(unitCoverage.map { it.executionData }, integrationCoverage.map { it.executionData })
    classDirectories.from(unitCoverage.map { it.classDirectories })
    sourceDirectories.from(unitCoverage.map { it.sourceDirectories })
    reports {
        xml.required = true
        html.required = true
    }
    dependsOn(coverageModules.filter { it != "integration-tests" }.map { ":$it:jacocoTestReport" })
}

// Consumer compatibility: publish every module into build/compat-repo and build small
// Gradle (Kotlin and Groovy DSL) and Maven projects against it. Slow, so not part of check.
val compatRepo = layout.buildDirectory.dir("compat-repo")
val cleanCompatRepo = tasks.register<Delete>("cleanCompatRepo") { delete(compatRepo) }
subprojects {
    tasks.withType<PublishToMavenRepository>().configureEach { mustRunAfter(cleanCompatRepo) }
}
val publishCompatRepo = tasks.register("publishCompatRepo") {
    group = "verification"
    description = "Publishes all modules to build/compat-repo."
    dependsOn(cleanCompatRepo)
    dependsOn(provider {
        subprojects.filter { it.pluginManager.hasPlugin("maven-publish") }
            .map { "${it.path}:publishAllPublicationsToCompatRepository" }
    })
}
val compatVersion = project.version.toString()
val compatGradle = listOf("gradle-kotlin", "gradle-groovy").map { consumer ->
    tasks.register<Exec>("compatibility-$consumer") {
        group = "verification"
        description = "Builds and runs the $consumer consumer against locally published artifacts."
        dependsOn(publishCompatRepo)
        workingDir = file("compatibility/$consumer")
        commandLine(file("gradlew").absolutePath, "--console=plain", "--no-daemon",
            "-PaxiomRepo=${compatRepo.get().asFile.toURI()}", "-PaxiomVersion=$compatVersion", "compatibilityCheck")
    }
}
val mavenAvailable = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "mvn").canExecute() }
val compatMaven = tasks.register<Exec>("compatibility-maven") {
    group = "verification"
    description = "Builds and runs the Maven consumer against locally published artifacts (needs mvn on PATH)."
    dependsOn(publishCompatRepo)
    enabled = mavenAvailable // skipped, with a SKIPPED status, where mvn is not installed
    workingDir = file("compatibility/maven")
    // --strict-checksums fails on a mismatch with the checksums the repositories publish; Maven has
    // no committed verification metadata, so this is the weaker equivalent (see docs/build.md).
    commandLine("mvn", "--batch-mode", "--no-transfer-progress", "--strict-checksums",
        "-Daxiom.repo=${compatRepo.get().asFile.toURI()}", "-Daxiom.version=$compatVersion",
        "-Dmaven.repo.local=${layout.buildDirectory.dir("compat-m2").get().asFile}", "verify")
}
tasks.register("compatibilityTest") {
    group = "verification"
    description = "Runs the Gradle (Kotlin, Groovy) and Maven consumer compatibility builds."
    dependsOn(compatGradle, compatMaven)
}
