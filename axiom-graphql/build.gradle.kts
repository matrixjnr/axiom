plugins {
    id("axiom.java-library")
    id("axiom.integration-test")
}

dependencies {
    api(project(":axiom-core"))
    // graphql-java and its transitive libraries stay inside this adapter; core never sees them.
    implementation(libs.graphql.java)
    // Tests drive the same routes through the in-memory client and a live listener.
    testImplementation(project(":axiom-test"))
    testRuntimeOnly(project(":axiom-http"))
}
