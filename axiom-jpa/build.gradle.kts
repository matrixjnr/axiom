plugins {
    id("axiom.java-library")
    id("axiom.integration-test")
}

dependencies {
    api(project(":axiom-core"))
    // The Jakarta Persistence API stays inside this adapter: Object and generics at the API boundary.
    implementation(libs.jakarta.persistence.api)
    // A real provider is resolved for tests only; the deterministic tests use proxy-backed fakes.
    testImplementation(libs.hibernate.core)
    // Axiom.create() discovers the runtime provider on the test classpath.
    testRuntimeOnly(project(":axiom-server"))
}
