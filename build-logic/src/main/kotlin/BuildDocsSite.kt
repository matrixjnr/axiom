import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Converts `README.md` and `docs/NAME.md` into the HTML pages of the documentation site (see
 * [DocsSite] and docs/site.md). It fails when a document links to a page or repository file that
 * does not exist.
 */
abstract class BuildDocsSite : DefaultTask() {
    /** The Markdown sources (declared so that a change reruns the task). */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val markdown: ConfigurableFileCollection

    /** Repository root; documents are read from it and repository links are checked against it. */
    @get:Internal abstract val root: DirectoryProperty

    /** `Group/docs-file.md` entries in display order; missing documents are skipped. */
    @get:Input abstract val navigation: ListProperty<String>

    /** Repository URL that links to non-page files point at, without a trailing slash. */
    @get:Input abstract val repository: Property<String>

    @get:Input abstract val branch: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    init {
        // Links to repository files that are not pages depend on the files present, not only on the
        // declared inputs, and generation is fast.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun build() {
        val base = root.get().asFile
        val result = DocsSite.build(DocsSite.sources(base), navigation.get(), repository.get(), branch.get()) { path ->
            val file = base.resolve(path)
            if (file.isFile) 1 else if (file.isDirectory) 2 else 0
        }
        if (result.problems.isNotEmpty()) {
            throw GradleException(result.problems.joinToString("\n", prefix = "The documentation has broken links:\n") { "  - $it" })
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        result.pages.forEach { (name, html) -> out.resolve(name).writeText(html) }
        val css = BuildDocsSite::class.java.getResourceAsStream("/docs-site/site.css")
            ?: throw GradleException("site.css is missing from the build logic resources")
        css.use { out.resolve("site.css").writeBytes(it.readBytes()) }
        // An optional custom domain file, added to docs/ only when the owner decides (docs/site.md).
        base.resolve("docs/CNAME").takeIf { it.isFile }?.copyTo(out.resolve("CNAME"))
    }
}
