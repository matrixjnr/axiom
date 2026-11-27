plugins { id("axiom.java-library") }

dependencies {
    api(project(":axiom-core"))
    // The registry is supplied by the application; no registry implementation is published here.
    implementation(libs.micrometer.core)
}
