plugins {
    id("axiom.java-library")
    id("axiom.integration-test")
}

dependencies {
    // java.sql is part of the JDK; the application supplies its own JDBC driver and DataSource.
    api(project(":axiom-core"))
    // Tests drive routes through the in-memory client and a live listener.
    testImplementation(project(":axiom-test"))
    testRuntimeOnly(project(":axiom-http"))
}
