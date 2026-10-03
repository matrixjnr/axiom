plugins {
    id("axiom.integration-test")
    id("axiom.module-boundaries")
}

dependencies {
    // Tests compile against the public API and the test client only, as an application would.
    testImplementation(project(":axiom-test"))
    // The Jakarta adapter is compiled against (it is the API under test) with the annotations it validates.
    testImplementation(project(":axiom-validation-jakarta"))
    testImplementation(libs.jakarta.validation.api)
    // The real transport and JSON codec are discovered at runtime, never compiled against.
    testRuntimeOnly(project(":axiom-http"))
    testRuntimeOnly(project(":axiom-json"))
}

// The module exists to run the assembled stack, so all of it is integration: `integrationTest`
// runs every class here (TestClient and live listener alike) and `test` runs nothing.
tasks.test { enabled = false }
tasks.named<Test>("integrationTest") {
    useJUnitPlatform { includeTags = mutableSetOf() }
    // Keeps the large-body cases from competing for CPU with the listener's own timing-sensitive tests.
    mustRunAfter(":axiom-http:integrationTest")
}
