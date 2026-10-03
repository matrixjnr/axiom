/** The allowed project dependencies per module; shared by the boundary and publication checks. */
object ModuleBoundaries {
    val allowed: Map<String, Set<String>> = mapOf(
        "axiom-core" to emptySet(),
        "axiom-server" to setOf("axiom-core"),
        "axiom-http" to setOf("axiom-core", "axiom-server"),
        "axiom-json" to setOf("axiom-core"),
        // The test client stays codec-neutral: tests send raw bodies and never need a serializer.
        "axiom-test" to setOf("axiom-core", "axiom-server", "axiom-http"),
        "axiom-starter" to setOf("axiom-core", "axiom-server", "axiom-http", "axiom-json"),
        // Black-box tests of the real codec over TestClient and a listener. Test-only: it has
        // no production code, and no module may depend on it.
        "integration-tests" to setOf("axiom-core", "axiom-http", "axiom-json", "axiom-test"),
        "axiom-validation" to setOf("axiom-core"),
        "axiom-validation-jakarta" to setOf("axiom-core", "axiom-validation")
    )

    /** Modules that exist only to run tests and are never published. */
    val testOnlyModules: Set<String> = setOf("integration-tests")
}
