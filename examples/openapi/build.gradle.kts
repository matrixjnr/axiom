plugins {
    application
    id("axiom.integration-test")
}

dependencies {
    implementation(project(":axiom-starter"))
    implementation(project(":axiom-openapi"))
    implementation(project(":axiom-openapi-ui"))
    implementation(project(":axiom-validation"))
    implementation(project(":axiom-security"))
    testImplementation(project(":axiom-test"))
}
application { mainClass.set("example.openapi.BooksApi") }
