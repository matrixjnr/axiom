// Cookie-authenticated browser application: sessions, CSRF protection and rate limiting from
// axiom-security, compiled and tested here so docs/security.md cannot quote code that does not build.
plugins {
    application
    id("axiom.java-test")
}

dependencies {
    implementation(project(":axiom-http"))
    implementation(project(":axiom-security"))
    testImplementation(project(":axiom-test"))
}
application { mainClass.set("example.browser.BrowserApp") }
