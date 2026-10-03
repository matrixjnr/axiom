plugins { id("axiom.java-library") }

// JDK only: JSON parsing and signature verification use no third-party library.
dependencies {
    api(project(":axiom-security"))
    testImplementation(project(":axiom-test"))
}
