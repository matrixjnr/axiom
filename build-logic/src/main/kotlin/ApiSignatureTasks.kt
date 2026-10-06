import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Reads class files without loading or initializing application classes. */
abstract class GenerateApiSignatures : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val javap: RegularFileProperty

    @get:OutputFile abstract val destination: RegularFileProperty

    @TaskAction fun generate() {
        val file = destination.get().asFile
        val text = ApiSignatures.inspect(javap.get().asFile, classes.files.sortedBy { it.path })
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}

/** Baseline approval is a separate task; check never overwrites the reviewed file. */
abstract class CheckApiSignatures : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseline: ConfigurableFileCollection

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val actual: RegularFileProperty

    @TaskAction fun check() {
        val expected = baseline.singleFile
        if (!expected.isFile) throw GradleException("Missing API baseline: $expected. Run apiUpdate and review the result.")
        val old = expected.readText().replace("\r\n", "\n").trimEnd()
        val new = actual.get().asFile.readText().trimEnd()
        if (old != new) throw GradleException("Public API changed: $expected\n" +
            ApiSignatures.differences(old, new) + "\nReview the generated API file, then run apiUpdate for an intentional change.")
    }
}
