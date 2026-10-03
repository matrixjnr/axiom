import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when `README.md` or a `docs/NAME.md` links to a file, a page or a `#anchor` that does not
 * exist, links to a site-absolute path, or lacks the front matter the site menu needs (see
 * [DocsLinks] and docs/site.md). It reads only the Markdown sources, so it is part of `check`.
 */
abstract class CheckDocsLinks : DefaultTask() {
    /** The Markdown sources (declared so that a change reruns the task). */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val markdown: ConfigurableFileCollection

    /** Repository root; repository links are checked against it. */
    @get:Internal abstract val root: DirectoryProperty

    @get:Input abstract val repository: Property<String>

    @get:Input abstract val branch: Property<String>

    init {
        // Links to repository files that are not pages depend on the files present, not only on the
        // declared inputs, and the check is fast.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val base = root.get().asFile
        val sources = DocsLinks.sources(base)
        val problems = mutableListOf<String>()
        for ((path, text) in sources) {
            DocsLinks.validate(path, text, problems)
            DocsLinks.rewrite(path, DocsLinks.splitFrontMatter(text).second, sources, repository.get(), branch.get(), {
                val file = base.resolve(it)
                if (file.isFile) 1 else if (file.isDirectory) 2 else 0
            }, problems)
        }
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n", prefix = "The documentation has broken links:\n") { "  - $it" })
        }
    }
}
