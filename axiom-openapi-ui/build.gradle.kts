plugins { id("axiom.java-library") }

dependencies {
    api(project(":axiom-core"))
    // The Swagger UI files, packaged as a WebJar and served from memory; nothing is loaded from a CDN.
    implementation(libs.swagger.ui)
    testImplementation(project(":axiom-test"))
}
