plugins { id("axiom.java-library") }

dependencies {
    implementation(project(":axiom-core"))
    implementation(project(":axiom-server"))
    implementation(project(":axiom-http"))
    implementation(project(":axiom-json"))
}
