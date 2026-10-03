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
include("axiom-metrics")
include("examples:hello", "examples:readme", "examples:rest-api", "examples:browser-app", "benchmarks:http")
