plugins { id("axiom.java-library") }

dependencies {
    api(project(":axiom-core"))
    // Rule sets are mapped to schema constraints; the module uses only the JDK besides.
    api(project(":axiom-validation"))
    // Tests serve documents through the test client and compare schemas with the real codec.
    testImplementation(project(":axiom-test"))
    testRuntimeOnly(project(":axiom-json"))
    // Only the annotations, to prove that renamed and hidden properties agree with the codec.
    testImplementation(platform(libs.jackson.bom))
    testImplementation(libs.jackson.annotations)
}
