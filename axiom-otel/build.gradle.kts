plugins { id("axiom.java-library") }

dependencies {
    api(project(":axiom-core"))
    // The adapter builds against the API only; no SDK or exporter is on the published classpath.
    implementation(libs.opentelemetry.api)
    // Tests export to in-memory readers; no agent, network or SDK dependency for applications.
    testImplementation(project(":axiom-test"))
    testImplementation(libs.opentelemetry.sdk.testing)
}
