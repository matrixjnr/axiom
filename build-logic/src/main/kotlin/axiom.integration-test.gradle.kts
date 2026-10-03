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

// Publishes the integrationTest execution data like the jacoco plugin does for `test`, so the
// root coverage aggregation can select it by test suite name.
configurations.consumable("coverageDataElementsForIntegrationTest") {
    description = "JaCoCo execution data of the integrationTest task."
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.VERIFICATION))
        attribute(TestSuiteName.TEST_SUITE_NAME_ATTRIBUTE, objects.named("integrationTest"))
        attribute(VerificationType.VERIFICATION_TYPE_ATTRIBUTE, objects.named(VerificationType.JACOCO_RESULTS))
    }
    outgoing.artifact(integrationTest.map { it.extensions.getByType<JacocoTaskExtension>().destinationFile!! }) {
        type = ArtifactTypeDefinition.BINARY_DATA_TYPE
        builtBy(integrationTest)
    }
}
