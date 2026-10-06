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
tasks.register<GenerateApiSignatures>("apiUpdate") {
    group = "verification"
    description = "Updates the API baseline; review and commit its diff with the API change."
    classes.from(main.map { it.output.classesDirs })
    javap.set(javapTool)
    inputs.property("jdkRuntimeVersion", launcher.map { it.metadata.javaRuntimeVersion })
    destination.set(baselineFile)
    // A restored or edited baseline must always be replaced by the actual current declarations.
    outputs.upToDateWhen { false }
}
tasks.named("check") { dependsOn(apiCheck) }
