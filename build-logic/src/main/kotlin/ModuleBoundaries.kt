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
        "integration-tests" to setOf("axiom-core", "axiom-http", "axiom-json", "axiom-test", "axiom-validation",
            "axiom-validation-jakarta"),
        "axiom-validation" to setOf("axiom-core"),
        "axiom-validation-jakarta" to setOf("axiom-core", "axiom-validation"),
        // Authentication, policies and HTTP security helpers; the JWT adapter builds on them and,
        // like every security module, uses only the JDK (no JSON or crypto library).
        "axiom-security" to setOf("axiom-core"),
        "axiom-security-jwt" to setOf("axiom-core", "axiom-security"),
        // In-memory metrics registry and Prometheus text rendering; JDK only, no metrics library.
        "axiom-metrics" to setOf("axiom-core"),
        // Opt-in third-party adapters: all external groups stay confined to the module that adapts them.
        "axiom-otel" to setOf("axiom-core"),
        "axiom-metrics-micrometer" to setOf("axiom-core"),
        // OpenAPI documents from route metadata; reads validation rules, writes JSON itself (JDK only).
        "axiom-openapi" to setOf("axiom-core", "axiom-validation"),
        // Serves Swagger UI from the application; the UI files come from one WebJar, nothing else external.
        "axiom-openapi-ui" to setOf("axiom-core"),
        // Runs gRPC services on their own listener; grpc-java's transport stays inside this adapter.
        "axiom-grpc" to setOf("axiom-core"),
        // Thin GraphQL HTTP adapter; graphql-java is confined to this module and core stays JDK-only.
        "axiom-graphql" to setOf("axiom-core")
    )

    /** Modules that exist only to run tests and are never published. */
    val testOnlyModules: Set<String> = setOf("integration-tests")
}
