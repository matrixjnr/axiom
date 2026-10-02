plugins { id("axiom.java-library") }

dependencies {
    implementation(project(":axiom-core"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
