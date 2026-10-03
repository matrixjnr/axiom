plugins {
    id("axiom.java-library")
    id("axiom.integration-test")
}

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.http)
    implementation(libs.netty.handler)
    // Optional native transports (ListenerOptions.transport): only their classes are needed to
    // compile. Applications opt in by adding netty-transport-native-epoll or -kqueue (with the
    // platform classifier) at runtime; nothing here forces them on users or appears in the POM.
    compileOnly(libs.netty.classes.epoll)
    compileOnly(libs.netty.classes.kqueue)
    api(project(":axiom-core"))
    implementation(project(":axiom-server"))
}

tasks.withType<Test>().configureEach {
    // The test LeakDetection extension also sets this; the property covers buffers created earlier.
    systemProperty("io.netty.leakDetection.level", "paranoid")
}

// --- Native transports (optional at runtime) -------------------------------------------------
// The integration tests run twice. `integrationTest` has no native library on its class path, so
// the default transport (AUTO) is NIO. `integrationTestNative` adds the native library of the build
// platform, so AUTO must pick epoll or kqueue and every existing real-socket test (TLS, linger,
// close, backpressure) runs on it. The task is skipped on platforms without a Netty native
// library, unless -Daxiom.requireNativeTransport=true, which makes a missing or unusable native
// transport a failure instead of a skip.
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val x86 = osArch == "amd64" || osArch == "x86_64"
val arm = osArch == "aarch64" || osArch == "arm64"
val nativeClassifier: String? = when {
    osName.contains("linux") && x86 -> "linux-x86_64"
    osName.contains("linux") && arm -> "linux-aarch_64"
    osName.contains("mac") && x86 -> "osx-x86_64"
    osName.contains("mac") && arm -> "osx-aarch_64"
    else -> null
}
val requireNative = providers.systemProperty("axiom.requireNativeTransport").map { it.toBoolean() }.getOrElse(false)

val nativeTransport by configurations.creating {
    description = "The Netty native transport of the build platform, added to the class path of integrationTestNative only."
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    nativeTransport(platform(libs.netty.bom))
    val classifier = nativeClassifier
    if (classifier != null) {
        val artifact = if (classifier.startsWith("linux")) "epoll" else "kqueue"
        nativeTransport("io.netty:netty-transport-native-$artifact::$classifier")
    }
}

val integrationTestNative = tasks.register<Test>("integrationTestNative") {
    group = "verification"
    description = "Runs the integration tests with the Netty native transport (epoll or kqueue) on the class path."
    val integration = tasks.named<Test>("integrationTest")
    testClassesDirs = integration.get().testClassesDirs
    classpath = integration.get().classpath + nativeTransport
    useJUnitPlatform { includeTags("integration") }
    systemProperty("axiom.expectedTransport", "NATIVE")
    // Only this task requires it: the plain integrationTest deliberately has no native library.
    systemProperty("axiom.requireNativeTransport", requireNative.toString())
    shouldRunAfter(integration)
    // Skipped where Netty has no native library, unless the native transport is required.
    enabled = nativeClassifier != null || requireNative
}
tasks.named("check") { dependsOn(integrationTestNative) }
