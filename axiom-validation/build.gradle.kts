plugins { id("axiom.java-library") }

dependencies {
    api(project(":axiom-core"))
    // End-to-end tests run handlers through the real dispatcher and problem mapping.
    testImplementation(project(":axiom-test"))
}
