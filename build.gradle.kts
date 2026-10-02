plugins { base }

val modules = subprojects.filter { it.buildFile.isFile }
tasks.named("check") { dependsOn(modules.map { "${it.path}:check" }) }
tasks.named("assemble") { dependsOn(modules.map { "${it.path}:assemble" }) }

// Consumer compatibility: publish every module into build/compat-repo and build small
// Gradle (Kotlin and Groovy DSL) and Maven projects against it. Slow, so not part of check.
val compatRepo = layout.buildDirectory.dir("compat-repo")
val cleanCompatRepo = tasks.register<Delete>("cleanCompatRepo") { delete(compatRepo) }
subprojects {
    tasks.withType<PublishToMavenRepository>().configureEach { mustRunAfter(cleanCompatRepo) }
}
val publishCompatRepo = tasks.register("publishCompatRepo") {
    group = "verification"
    description = "Publishes all modules to build/compat-repo."
    dependsOn(cleanCompatRepo)
    dependsOn(provider {
        subprojects.filter { it.pluginManager.hasPlugin("maven-publish") }
            .map { "${it.path}:publishAllPublicationsToCompatRepository" }
    })
}
val compatVersion = project.version.toString()
val compatGradle = listOf("gradle-kotlin", "gradle-groovy").map { consumer ->
    tasks.register<Exec>("compatibility-$consumer") {
        group = "verification"
        description = "Builds and runs the $consumer consumer against locally published artifacts."
        dependsOn(publishCompatRepo)
        workingDir = file("compatibility/$consumer")
        commandLine(file("gradlew").absolutePath, "--console=plain", "--no-daemon",
            "-PaxiomRepo=${compatRepo.get().asFile.toURI()}", "-PaxiomVersion=$compatVersion", "compatibilityCheck")
    }
}
val mavenAvailable = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "mvn").canExecute() }
val compatMaven = tasks.register<Exec>("compatibility-maven") {
    group = "verification"
    description = "Builds and runs the Maven consumer against locally published artifacts (needs mvn on PATH)."
    dependsOn(publishCompatRepo)
    enabled = mavenAvailable // skipped, with a SKIPPED status, where mvn is not installed
    workingDir = file("compatibility/maven")
    commandLine("mvn", "--batch-mode", "--no-transfer-progress",
        "-Daxiom.repo=${compatRepo.get().asFile.toURI()}", "-Daxiom.version=$compatVersion",
        "-Dmaven.repo.local=${layout.buildDirectory.dir("compat-m2").get().asFile}", "verify")
}
tasks.register("compatibilityTest") {
    group = "verification"
    description = "Runs the Gradle (Kotlin, Groovy) and Maven consumer compatibility builds."
    dependsOn(compatGradle, compatMaven)
}
