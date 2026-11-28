plugins {
    id("axiom.java-library")
    id("axiom.integration-test")
}

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.http)
    implementation(libs.netty.http2)
    implementation(libs.netty.handler)
    // Optional native transports (ListenerOptions.transport): only their classes are needed to
    // compile. Applications opt in by adding netty-transport-native-epoll, -kqueue or
    // -io_uring (with the platform classifier) at runtime; nothing here forces them on users or
    // appears in the POM.
    compileOnly(libs.netty.classes.epoll)
    compileOnly(libs.netty.classes.kqueue)
    compileOnly(libs.netty.classes.iouring)
    api(project(":axiom-core"))
    implementation(project(":axiom-server"))
}

tasks.withType<Test>().configureEach {
    // The test LeakDetection extension also sets this; the property covers buffers created earlier.
    systemProperty("io.netty.leakDetection.level", "paranoid")
}

// --- Native transports (optional at runtime) -------------------------------------------------
// The integration tests run three times. `integrationTest` has no native library on its class
// path, so the default transport (AUTO) is NIO. `integrationTestNative` adds the native library of
// the build platform, so AUTO must pick epoll or kqueue and every existing real-socket test (TLS,
// linger, close, backpressure) runs on it; on Linux the io_uring library is added as well, so the
// explicit IO_URING cases run too. `integrationTestIoUring` adds only io_uring, so AUTO must select
// it where the kernel permits. Either native task is skipped on platforms without the matching
// Netty native library, unless -Daxiom.requireNativeTransport=true, which makes a missing or
// unusable native transport a failure instead of a skip.
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val x86 = osArch == "amd64" || osArch == "x86_64"
val arm = osArch == "aarch64" || osArch == "arm64"
val riscv = osArch == "riscv64"
val nativeClassifier: String? = when {
    osName.contains("linux") && x86 -> "linux-x86_64"
    osName.contains("linux") && arm -> "linux-aarch_64"
    osName.contains("mac") && x86 -> "osx-x86_64"
    osName.contains("mac") && arm -> "osx-aarch_64"
    else -> null
}
val ioUringClassifier: String? = when {
    osName.contains("linux") && x86 -> "linux-x86_64"
    osName.contains("linux") && arm -> "linux-aarch_64"
    osName.contains("linux") && riscv -> "linux-riscv64"
    else -> null
}
val requireNative = providers.systemProperty("axiom.requireNativeTransport").map { it.toBoolean() }.getOrElse(false)

val nativeTransport by configurations.creating {
    description = "The Netty native transports of the build platform, added to integrationTestNative only."
    isCanBeConsumed = false
    isCanBeResolved = true
}
val ioUringTransport by configurations.creating {
    description = "Netty's io_uring transport, added to integrationTestIoUring only."
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
    val uringClassifier = ioUringClassifier
    if (uringClassifier != null) {
        nativeTransport("io.netty:netty-transport-native-io_uring::$uringClassifier")
        ioUringTransport(platform(libs.netty.bom))
        ioUringTransport("io.netty:netty-transport-native-io_uring::$uringClassifier")
    }
}

val integrationTestNative = tasks.register<Test>("integrationTestNative") {
    group = "verification"
    description = "Runs the integration tests with the Netty native transport (epoll or kqueue, plus io_uring on Linux) on the class path."
    val integration = tasks.named<Test>("integrationTest")
    testClassesDirs = integration.get().testClassesDirs
    classpath = integration.get().classpath + nativeTransport
    useJUnitPlatform { includeTags("integration") }
    systemProperty("axiom.expectedTransport", "NATIVE")
    // Only the native tasks require it: plain integrationTest deliberately has no native library.
    systemProperty("axiom.requireNativeTransport", requireNative.toString())
    shouldRunAfter(integration)
    // Skipped where Netty has no native library, unless the native transport is required.
    enabled = nativeClassifier != null || requireNative
}
val integrationTestIoUring = tasks.register<Test>("integrationTestIoUring") {
    group = "verification"
    description = "Runs the integration tests with Netty's io_uring transport (Linux) on the class path."
    val integration = tasks.named<Test>("integrationTest")
    testClassesDirs = integration.get().testClassesDirs
    classpath = integration.get().classpath + ioUringTransport
    useJUnitPlatform { includeTags("integration") }
    systemProperty("axiom.expectedTransport", "IO_URING")
    systemProperty("axiom.requireNativeTransport", requireNative.toString())
    shouldRunAfter(integrationTestNative)
    // io_uring is Linux-only; a required native transport turns the skip into a failure in the tests.
    enabled = ioUringClassifier != null || requireNative
}
tasks.named("check") {
    dependsOn(integrationTestNative, integrationTestIoUring)
}
