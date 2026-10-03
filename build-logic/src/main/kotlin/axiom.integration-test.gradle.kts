// For modules with test classes tagged @Tag("integration") (real sockets or a live listener):
// registers `integrationTest`, which runs only those classes from the test source set, and adds
// it to `check`. `test` excludes them (see axiom.java-test).
plugins { id("axiom.java-test") }

val sourceSets = extensions.getByType<SourceSetContainer>()
val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs the tests tagged integration (real sockets, live listeners)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.named("test"))
}
tasks.named("check") { dependsOn(integrationTest) }
tasks.named("checkIntegrationTags") { enabled = false }
