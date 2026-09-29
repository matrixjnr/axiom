plugins { java }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
