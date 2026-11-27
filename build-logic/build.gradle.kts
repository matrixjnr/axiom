plugins { `kotlin-dsl` }

repositories { gradlePluginPortal() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

// Unit tests of the convention checks (publication coverage, module boundaries). The root build
// runs them as part of its `check` (see the root build.gradle.kts).
dependencies {
    // Precompiled script plugins apply Spotless by id, so it must be on this build's classpath.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.12.0")
    implementation(libs.japicmp) {
        // Comparisons use the Java API and text reports; JAXB's XML runtime is unnecessary.
        exclude(group = "org.glassfish.jaxb", module = "jaxb-runtime")
    }
    implementation(libs.asm.tree)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.test { useJUnitPlatform() }
