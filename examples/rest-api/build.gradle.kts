plugins {
    application
    id("axiom.integration-test")
}

dependencies {
    implementation(project(":axiom-http"))
    // The JSON codec is discovered at startup; application code never compiles against it.
    runtimeOnly(project(":axiom-json"))
    testImplementation(project(":axiom-test"))
}
application { mainClass.set("example.rest.NotesApi") }
