plugins {
    java
    checkstyle
}

// Style gate: checkstyleMain and checkstyleTest are part of `check` and fail on any violation.
// One shared ruleset (config/checkstyle/checkstyle.xml) covers every Java module.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    configDirectory.set(rootProject.layout.projectDirectory.dir("config/checkstyle"))
    maxWarnings = 0
    maxErrors = 0
}

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
