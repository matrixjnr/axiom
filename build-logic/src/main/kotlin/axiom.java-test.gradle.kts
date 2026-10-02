plugins { id("axiom.java-base") }

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // When set (CI does), allocation-based tests fail instead of being skipped on a JVM that
    // cannot measure per-thread allocation. Forwarded from the Gradle command line: -D...=true.
    systemProperty("axiom.requireAllocationTests",
        providers.systemProperty("axiom.requireAllocationTests").getOrElse("false"))
}
