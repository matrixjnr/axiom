pluginManagement { includeBuild("build-logic") }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "axiom"
include("axiom-core", "axiom-server", "axiom-http", "axiom-json", "axiom-test", "axiom-bom")
include("axiom-starter")
include("integration-tests")
include("axiom-validation")
include("examples:hello", "examples:rest-api", "benchmarks:http")
