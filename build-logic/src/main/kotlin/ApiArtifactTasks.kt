import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class CaptureApiArtifact : DefaultTask() {
    @get:Input abstract val moduleName: Property<String>
    @get:Input abstract val artifactVersion: Property<String>
    @get:Input @get:Optional abstract val retainedRoot: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archive: RegularFileProperty
    @get:Classpath abstract val dependencies: ConfigurableFileCollection
    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction fun capture() {
        ApiArtifactBundle.capture(moduleName.get(), artifactVersion.get(), archive.get().asFile,
            dependencies.files.toList(), destination.get().asFile, retainedRoot.orNull?.let(::File))
    }
}

abstract class WriteApiArtifactInventory : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifests: ConfigurableFileCollection
    @get:OutputFile abstract val destination: RegularFileProperty
    @TaskAction fun write() { ApiArtifactInventory.write(manifests.files.toList(), destination.get().asFile) }
}

abstract class CheckApiArtifactInventory : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val baseline: DirectoryProperty
    @get:Input abstract val baselineVersion: Property<String>
    @get:Input abstract val currentModules: ListProperty<String>
    @TaskAction fun check() {
        ApiArtifactInventory.requireModules(baseline.get().asFile, baselineVersion.get(), currentModules.get().toSet())
    }
}

abstract class CheckApiArtifactCompatibility : DefaultTask() {
    @get:Input abstract val moduleName: Property<String>
    @get:Input abstract val baselineVersion: Property<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val baseline: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val current: DirectoryProperty
    @get:OutputFile abstract val report: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun compare() {
        try {
            require(baseline.get().asFile.canonicalFile != current.get().asFile.canonicalFile) { "Baseline must be retained separately" }
            val old = ApiArtifactBundle.read(baseline.get().asFile, moduleName.get(), baselineVersion.get())
            val new = ApiArtifactBundle.read(current.get().asFile, moduleName.get())
            val result = CompareApiArtifacts.compare(old, new)
            val output = report.get().asFile
            output.parentFile.mkdirs()
            output.writeText(result.report(old, new))
            if (!result.compatible) throw GradleException("API artifact compatibility failed for ${moduleName.get()}; see $output")
        } catch (failure: Exception) {
            val output = report.get().asFile
            if (failure !is GradleException) {
                output.parentFile.mkdirs()
                output.writeText("Artifact comparison could not complete: ${failure.message}\n")
            }
            throw GradleException("${moduleName.get()}: ${failure.message}", failure)
        }
    }
}
