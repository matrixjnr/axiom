plugins { id("axiom.java-library") }

dependencies {
    api(project(":axiom-core"))
    // End-to-end tests record a real application's requests through the test client.
    testImplementation(project(":axiom-test"))
}
