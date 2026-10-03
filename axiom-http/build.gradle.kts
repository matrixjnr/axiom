plugins {
    id("axiom.java-library")
    id("axiom.integration-test")
}

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.http)
    implementation(libs.netty.handler)
    api(project(":axiom-core"))
    implementation(project(":axiom-server"))
}

tasks.withType<Test>().configureEach {
    // The test LeakDetection extension also sets this; the property covers buffers created earlier.
    systemProperty("io.netty.leakDetection.level", "paranoid")
}
