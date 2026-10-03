plugins { id("axiom.java-library") }

// Published as com.jsgalactic.axiom:axiom. Application code compiles against core only; the HTTP
// transport, the default runtime and the JSON codec are discovered at run time.
dependencies {
    api(project(":axiom-core"))
    runtimeOnly(project(":axiom-http"))
    runtimeOnly(project(":axiom-server"))
    runtimeOnly(project(":axiom-json"))
}
