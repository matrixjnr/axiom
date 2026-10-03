import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a generated page links to a file or an anchor that does not exist in the site, or to
 * a site-absolute path (which breaks under a project path such as `/axiom/`). External links
 * (`http:`, `https:`, `mailto:`) are not fetched. Links into `api/` (the aggregated Javadoc) are
 * verified against the files when [verifyApi] is set and otherwise accepted.
 */
abstract class CheckSiteLinks : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val siteDir: DirectoryProperty

    @get:Input abstract val verifyApi: Property<Boolean>

    init {
        // The Javadoc directory is checked by existence, not declared as an input.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val root = siteDir.get().asFile
        val pages = root.listFiles { f -> f.isFile && f.name.endsWith(".html") }.orEmpty().associate { it.name to it.readText() }
        val problems = problems(pages, verifyApi.get()) { path -> root.resolve(path).exists() }
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n", prefix = "The site has broken links:\n") { "  - $it" })
        }
    }

    companion object {
        private val ATTRIBUTE = Regex("""\b(href|src)="([^"]*)"""")
        private val ID = Regex("""\bid="([^"]*)"""")
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        /**
         * The problems of [pages] (file name to HTML); [exists] tells whether a path relative to the
         * site root exists, for links that leave the page set (stylesheet, Javadoc).
         */
        fun problems(pages: Map<String, String>, verifyApi: Boolean, exists: (String) -> Boolean): List<String> {
            val ids = pages.mapValues { (_, html) -> ID.findAll(html).map { unescape(it.groupValues[1]) }.toList() }
            val problems = mutableListOf<String>()
            for ((name, html) in pages.toSortedMap()) {
                ids.getValue(name).groupBy { it }.filterValues { it.size > 1 }.keys
                    .forEach { problems += "$name: duplicate id '$it'" }
                for (match in ATTRIBUTE.findAll(html)) {
                    val link = unescape(match.groupValues[2])
                    if (link.isEmpty() || SCHEME.containsMatchIn(link)) continue
                    if (link.startsWith("/")) {
                        problems += "$name: '$link' is site-absolute and breaks under a project path"
                        continue
                    }
                    val path = link.substringBefore('#').substringBefore('?')
                    val fragment = if (link.contains('#')) link.substringAfter('#') else null
                    val target = if (path.isEmpty()) name else path
                    if (target in pages) {
                        if (!fragment.isNullOrEmpty() && fragment !in ids.getValue(target)) {
                            problems += "$name: '$link' points to a missing anchor in $target"
                        }
                    } else if (target.startsWith("api/") && !verifyApi) {
                        continue
                    } else if (!exists(target)) {
                        problems += "$name: '$link' points to a file that does not exist"
                    }
                }
            }
            return problems
        }

        private fun unescape(s: String) =
            s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
