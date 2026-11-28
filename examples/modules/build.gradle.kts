// Every opt-in adapter wired into one small application, with in-memory tests. Nothing here is
// published; the run* tasks start one example at a time.
plugins { id("axiom.java-test") }

dependencies {
    implementation(project(":axiom-http"))
    implementation(project(":axiom-otel"))
    implementation(project(":axiom-metrics-micrometer"))
    implementation(project(":axiom-grpc"))
    implementation(project(":axiom-graphql"))
    implementation(project(":axiom-jdbc"))
    implementation(project(":axiom-jta"))
    implementation(project(":axiom-jpa"))
    // Adapter dependencies are opt-in, so an application declares the types it uses directly.
    implementation(libs.opentelemetry.api)
    implementation(libs.micrometer.core)
    implementation(libs.grpc.stub)
    implementation(libs.graphql.java)
    implementation(libs.jakarta.transaction.api)
    implementation(libs.jakarta.persistence.api)

    testImplementation(project(":axiom-test"))
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.grpc.inprocess)
}

val examples =
    listOf(
        "Otel" to "example.modules.OtelExample",
        "Micrometer" to "example.modules.MicrometerExample",
        "Grpc" to "example.modules.GrpcExample",
        "GraphQl" to "example.modules.GraphQlExample",
        "Jdbc" to "example.modules.JdbcExample",
        "Jta" to "example.modules.JtaExample",
        "Jpa" to "example.modules.JpaExample",
    )
examples.forEach { (name, mainClass) ->
    tasks.register<JavaExec>("run$name") {
        group = "application"
        description = "Runs the $name opt-in module example."
        classpath = sourceSets["main"].runtimeClasspath
        this.mainClass.set(mainClass)
    }
}
