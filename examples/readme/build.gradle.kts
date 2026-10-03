// The README's Hello world and tasks API, compiled and tested here; the root `checkReadmeSnippets`
// task fails when the README quotes something different from these sources.
plugins { id("axiom.integration-test") }

dependencies {
    implementation(project(":axiom-starter"))
    testImplementation(project(":axiom-test"))
}
