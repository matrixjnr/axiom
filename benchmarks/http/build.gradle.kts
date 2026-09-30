plugins { id("axiom.java-test") }

val jmh by sourceSets.creating

dependencies {
    "jmhImplementation"(project(":axiom-http"))
    "jmhImplementation"(libs.jmh.core)
    "jmhAnnotationProcessor"(libs.jmh.generator)
}

// Compile the harness in normal checks; timing measurements remain an explicit local task.
tasks.named("check") { dependsOn(jmh.classesTaskName) }

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH routing and in-memory dispatch benchmarks."
    classpath = jmh.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
