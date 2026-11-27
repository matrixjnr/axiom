plugins {
    java
    checkstyle
    id("com.diffplug.spotless")
}

// Style gate: Google Java Style through google-java-format and the official Checkstyle ruleset,
// plus Axiom's explicit no-single-line-methods rule. Both run as part of `check`.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    configFile = rootProject.layout.projectDirectory.file("config/checkstyle/google_checks.xml").asFile
    maxWarnings = 0
    maxErrors = 0
}
spotless {
    java {
        googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion)
    }
}
tasks.named("check") { dependsOn(tasks.named("spotlessCheck")) }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.withType<AbstractArchiveTask>().configureEach {
    filePermissions { unix("rw-r--r--") }
    dirPermissions { unix("rwxr-xr-x") }
}
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addBooleanOption("notimestamp", true)
}
