plugins { `kotlin-dsl` }

repositories { gradlePluginPortal() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

// Unit tests of the convention checks (publication coverage, module boundaries). The root build
// runs them as part of its `check` (see the root build.gradle.kts).
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.test { useJUnitPlatform() }
