plugins {
    id("axiom.java-library")
}

dependencies {
    api(project(":axiom-core"))
    // Only the JTA API is pinned here. A transaction manager (Narayana, Atomikos, ...) is owned
    // by the application and is never a dependency of this module or of core.
    implementation(libs.jakarta.transaction.api)
    // Tests drive the middleware through the in-memory client, without a listener or a provider.
    testImplementation(project(":axiom-test"))
}
