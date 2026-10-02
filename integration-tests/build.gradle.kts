plugins {
    id("axiom.java-test")
    id("axiom.module-boundaries")
}

dependencies {
    // Tests compile against the public API and the test client only, as an application would.
    testImplementation(project(":axiom-test"))
    // The real transport and JSON codec are discovered at runtime, never compiled against.
    testRuntimeOnly(project(":axiom-http"))
    testRuntimeOnly(project(":axiom-json"))
}

tasks.test {
    // Keeps the large-body cases from competing for CPU with the listener's own timing-sensitive tests.
    mustRunAfter(":axiom-http:test")
}
