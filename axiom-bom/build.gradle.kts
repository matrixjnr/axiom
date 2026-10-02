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
    }
}
