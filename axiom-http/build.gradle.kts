plugins { id("axiom.java-library") }

dependencies {
    api(project(":axiom-core"))
    runtimeOnly(project(":axiom-server"))
}
