plugins { id("axiom.java-test") }

dependencies {
    implementation(project(":axiom-http"))
    // The JSON codec is discovered at startup; application code never compiles against it.
    runtimeOnly(project(":axiom-json"))
}
