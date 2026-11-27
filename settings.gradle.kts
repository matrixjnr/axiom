pluginManagement { includeBuild("build-logic") }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "axiom"
include("axiom-core", "axiom-server", "axiom-http", "axiom-json", "axiom-test", "axiom-bom")
include("axiom-starter")
include("integration-tests")
include("axiom-validation", "axiom-validation-jakarta")
include("axiom-security", "axiom-security-jwt")
include("axiom-metrics", "axiom-metrics-micrometer", "axiom-otel")
include("axiom-openapi", "axiom-openapi-ui")
include("axiom-grpc")
include("examples:hello", "examples:readme", "examples:rest-api", "examples:browser-app", "examples:openapi",
    "benchmarks:http")
