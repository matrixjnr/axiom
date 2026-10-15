plugins { java }

val main = extensions.getByType<SourceSetContainer>().named("main")
val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val javapTool = launcher.map {
    val bin = it.metadata.installationPath.dir("bin")
    bin.file(if (bin.file("javap.exe").asFile.isFile) "javap.exe" else "javap")
}
val baselineFile = rootProject.layout.projectDirectory.file("api/${project.name}.api")
val apiDump = tasks.register<GenerateApiSignatures>("apiDump") {
    group = "verification"
    description = "Writes current public/protected API signatures for review."
    classes.from(main.map { it.output.classesDirs })
    javap.set(javapTool)
    inputs.property("jdkRuntimeVersion", launcher.map { it.metadata.javaRuntimeVersion })
    destination.set(layout.buildDirectory.file("api/current.api"))
}
val apiCheck = tasks.register<CheckApiSignatures>("apiCheck") {
    group = "verification"
    description = "Rejects unreviewed changes to the exported API signatures."
    baseline.from(baselineFile)
    actual.set(apiDump.flatMap { it.destination })
}
val apiUpdate = tasks.register<GenerateApiSignatures>("apiUpdate") {
    group = "verification"
    description = "Updates the API baseline; review and commit its diff with the API change."
    classes.from(main.map { it.output.classesDirs })
    javap.set(javapTool)
    inputs.property("jdkRuntimeVersion", launcher.map { it.metadata.javaRuntimeVersion })
    destination.set(baselineFile)
    // A restored or edited baseline must always be replaced by the actual current declarations.
    outputs.upToDateWhen { false }
}
// If both are explicitly requested, finish the approved update before reading its baseline.
// Ordering alone never schedules an update during an ordinary check.
apiCheck.configure { mustRunAfter(apiUpdate) }
tasks.named("check") { dependsOn(apiCheck) }

val retainedApiRoot = providers.gradleProperty("apiBaselineDir")
val artifactRootDirectory = rootProject.layout.projectDirectory
val artifactModuleName = project.name
val captureApiArtifact = tasks.register<CaptureApiArtifact>("captureApiArtifact") {
    group = "verification"
    description = "Captures this module's JAR and exact runtime dependencies as a checksummed bundle."
    moduleName.set(project.name)
    artifactVersion.set(providers.gradleProperty("apiArtifactVersion").orElse(project.version.toString()))
    archive.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    dependencies.from(configurations.named("runtimeClasspath"))
    destination.set(rootProject.layout.buildDirectory.dir("api-artifacts/${project.name}"))
    retainedRoot.set(retainedApiRoot.map { artifactRootDirectory.dir(it).asFile.absolutePath })
}
val apiArtifactCheck = tasks.register<CheckApiArtifactCompatibility>("apiArtifactCheck") {
    group = "verification"
    description = "Compares current artifacts with a separately retained, versioned baseline."
    moduleName.set(project.name)
    baselineVersion.set(providers.gradleProperty("apiBaselineVersion"))
    baseline.set(retainedApiRoot.map { artifactRootDirectory.dir(it).dir(artifactModuleName) })
    current.set(captureApiArtifact.flatMap { it.destination })
    report.set(layout.buildDirectory.file("reports/api-artifacts/comparison.txt"))
}
// Artifact comparison is mandatory whenever a retained baseline is supplied. It never creates one.
if (retainedApiRoot.isPresent) tasks.named("check") { dependsOn(apiArtifactCheck) }
