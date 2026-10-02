plugins { id("axiom.java-library") }

dependencies {
    implementation(project(":axiom-core"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jdk8)
    implementation(libs.jackson.datatype.jsr310)
}
