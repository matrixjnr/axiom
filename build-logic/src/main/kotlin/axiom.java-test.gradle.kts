plugins {
    id("axiom.java-base")
    jacoco
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
// Every Test task (test and integrationTest) runs with the JaCoCo agent and writes
// build/jacoco/<task>.exec. No class is excluded from coverage.
jacoco { toolVersion = libs.findVersion("jacoco").get().requiredVersion }
dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // When set (CI does), allocation-based tests fail instead of being skipped on a JVM that
    // cannot measure per-thread allocation. Forwarded from the Gradle command line: -D...=true.
    systemProperty("axiom.requireAllocationTests",
        providers.systemProperty("axiom.requireAllocationTests").getOrElse("false"))
}

// `test` holds the fast tests: pure unit tests, embedded channels and the in-memory TestClient.
// Test classes that open real sockets or start a live listener carry @Tag("integration") and
// run in the `integrationTest` task that the axiom.integration-test convention registers.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
}

// Without axiom.integration-test, tagged classes would run in no task at all. This check fails
// instead; that convention disables it.
val checkIntegrationTags = tasks.register("checkIntegrationTags") {
    group = "verification"
    description = "Fails when a test class is tagged integration but the module has no integrationTest task."
    val testSources = fileTree("src/test/java") { include("**/*.java") }
    inputs.files(testSources)
    doLast {
        val tagged = testSources.files.filter { it.readText().contains("Tag(\"integration\")") }
        if (tagged.isNotEmpty()) {
            throw GradleException("Tests tagged integration need the axiom.integration-test plugin, " +
                "or they never run: ${tagged.joinToString { it.name }}")
        }
    }
}
tasks.named("check") { dependsOn(checkIntegrationTags) }

// Per-module coverage: build/reports/jacoco/test/{html,jacocoTestReport.xml} from the execution
// data of every Test task in the module. Runs those tests; not part of check.
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.withType<Test>())
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") })
    reports {
        xml.required = true
        html.required = true
    }
}
