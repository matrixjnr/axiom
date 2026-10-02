// Standalone consumer of locally published artifacts; not part of the main build.
// Pass -PaxiomRepo=<directory> and -PaxiomVersion=<version>.
pluginManagement { repositories { gradlePluginPortal() } }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(providers.gradleProperty("axiomRepo").get())
            content { includeGroup("io.axiom") }
        }
        mavenCentral()
    }
}

rootProject.name = "consumer-gradle-kotlin"
