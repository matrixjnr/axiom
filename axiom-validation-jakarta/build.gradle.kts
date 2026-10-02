plugins { id("axiom.java-library") }

dependencies {
    api(project(":axiom-validation"))
    // Applications declare jakarta.validation-api themselves to annotate their types; no Jakarta
    // or Hibernate type appears in this module's API. Expression Language is deliberately absent.
    implementation(libs.jakarta.validation.api)
    implementation(libs.hibernate.validator)
    testImplementation(project(":axiom-test"))
}
