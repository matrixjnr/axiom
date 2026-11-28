plugins {
    `java-platform`
    id("axiom.publish")
}

dependencies {
    constraints {
        api(project(":axiom-core"))
        api(project(":axiom-server"))
        api(project(":axiom-http"))
        api(project(":axiom-json"))
        api(project(":axiom-test"))
        api(project(":axiom-starter"))
        api(project(":axiom-validation"))
        api(project(":axiom-validation-jakarta"))
        api(project(":axiom-security"))
        api(project(":axiom-security-jwt"))
        api(project(":axiom-metrics"))
        api(project(":axiom-metrics-micrometer"))
        api(project(":axiom-otel"))
        api(project(":axiom-openapi"))
        api(project(":axiom-openapi-ui"))
        api(project(":axiom-grpc"))
        api(project(":axiom-graphql"))
        api(project(":axiom-jdbc"))
        api(project(":axiom-jta"))
    }
}
