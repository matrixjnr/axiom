plugins { java }

val axiomVersion = providers.gradleProperty("axiomVersion").get()

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
sourceSets.main { java.srcDirs("../shared", "../shared-validation") }

dependencies {
    // The BOM supplies the version of the starter.
    implementation(platform("io.axiom:axiom-bom:$axiomVersion"))
    implementation("io.axiom:axiom")
    // Optional modules, versions from the BOM. Applications declare the annotation API themselves.
    implementation("io.axiom:axiom-validation")
    implementation("io.axiom:axiom-validation-jakarta")
    implementation("jakarta.validation:jakarta.validation-api:3.1.1")
}

val smoke = tasks.register<JavaExec>("smoke") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.Smoke")
}

val validationSmokes = listOf("ValidationSmoke", "JakartaSmoke").map { name ->
    tasks.register<JavaExec>("run$name") {
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("consumer.$name")
    }
}

val compileFiles = configurations.compileClasspath.map { c -> c.files.map { it.name } }
val rootComponent = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }

// Checks what the starter exposes: only Axiom core at compile time, Netty and Jackson at
// run time, and resolution through Gradle module metadata rather than the POM.
val verifyClasspaths = tasks.register("verifyClasspaths") {
    val compile = compileFiles
    val root = rootComponent
    val runtime = configurations.runtimeClasspath.map { c -> c.files.map { it.name } }
    doLast {
        val compileNames = compile.get()
        check(compileNames.any { it.startsWith("axiom-core") }) { "axiom-core missing from compile classpath" }
        check(compileNames.none { it.startsWith("netty") || it.startsWith("jackson") || it.startsWith("axiom-http") }) {
            "implementation detail leaked to the compile classpath: $compileNames"
        }
        val runtimeNames = runtime.get()
        for (prefix in listOf("axiom-http", "axiom-server", "axiom-json", "netty-codec-http", "jackson-databind")) {
            check(runtimeNames.any { it.startsWith(prefix) }) { "$prefix missing from runtime classpath: $runtimeNames" }
        }
        val starter = root.get().dependencies
            .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
            .map { it.selected }
            .first { it.moduleVersion?.name == "axiom" }
        val variants = starter.variants.map { it.displayName }
        check(variants.any { it == "runtimeElements" }) { "starter was not resolved from Gradle module metadata: $variants" }
        println("VERIFY OK compile=$compileNames runtime=${runtimeNames.size} files")
    }
}

tasks.register("compatibilityCheck") { dependsOn(verifyClasspaths, smoke, validationSmokes) }
