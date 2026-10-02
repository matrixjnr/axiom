plugins { id("axiom.java-library") }

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.http)
    implementation(libs.netty.handler)
    api(project(":axiom-core"))
    implementation(project(":axiom-server"))
}

tasks.test {
    // The test LeakDetection extension also sets this; the property covers buffers created earlier.
    systemProperty("io.netty.leakDetection.level", "paranoid")
}
