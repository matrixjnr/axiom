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
 * Fails when a page of the built site (the output of Jekyll with the aggregated Javadoc copied to
 * `api/`, see docs/site.md) links to a file or an anchor that does not exist, or to a path outside
 * the site's base path. External links (`http:`, `https:`, `mailto:`) are not fetched. The Javadoc's
 * own pages are not scanned; links into it must resolve to a file.
 */
abstract class CheckSiteLinks : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val siteDir: DirectoryProperty

    /** The public address of the site (`https://matrixjnr.github.io/axiom`); links to it are checked as local ones. */
    @get:Input abstract val siteUrl: Property<String>

    /** The site's base path (`/axiom`), or empty when it is served from the root of a domain. */
    @get:Input abstract val baseUrl: Property<String>

    init {
        // The Javadoc directory is checked by existence, not declared as an input.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val root = siteDir.get().asFile
        val pages = root.walkTopDown().filter { it.isFile && it.name.endsWith(".html") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { !it.startsWith("api/") }
            .associateWith { root.resolve(it).readText() }
        val problems = problems(pages, siteUrl.get(), baseUrl.get()) { path -> root.resolve(path).isFile }
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n", prefix = "The site has broken links:\n") { "  - $it" })
        }
    }

    companion object {
        private val ATTRIBUTE = Regex("""\b(href|src)="([^"]*)"""")
        private val ID = Regex("""\bid="([^"]*)"""")
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        /**
         * The problems of [pages] (path relative to the site root to HTML); [isFile] tells whether a
         * path relative to the site root is a file, for links that leave the page set (stylesheets,
         * scripts, images, Javadoc).
         */
        fun problems(pages: Map<String, String>, siteUrl: String, baseUrl: String, isFile: (String) -> Boolean): List<String> {
            val ids = pages.mapValues { (_, html) -> ID.findAll(html).map { unescape(it.groupValues[1]) }.toSet() }
            val problems = mutableListOf<String>()
            for ((name, html) in pages.toSortedMap()) {
                for (match in ATTRIBUTE.findAll(html)) {
                    val link = unescape(match.groupValues[2]).let { if (it.startsWith("$siteUrl/") || it == siteUrl) it.removePrefix(siteUrl.removeSuffix(baseUrl)) else it }
                    if (link.isEmpty() || link.startsWith("//") || SCHEME.containsMatchIn(link)) continue
                    val fragment = if (link.contains('#')) link.substringAfter('#') else null
                    var path = link.substringBefore('#').substringBefore('?')
                    if (path.startsWith("/")) {
                        if (baseUrl.isNotEmpty() && path != baseUrl && !path.startsWith("$baseUrl/")) {
                            problems += "$name: '$link' is outside the site's base path $baseUrl"
                            continue
                        }
                        path = path.removePrefix(baseUrl).removePrefix("/")
                    } else if (path.isNotEmpty()) {
                        path = DocsLinks.normalize(name.substringBeforeLast('/', "") + "/" + path).orEmpty()
                    }
                    var target = if (link.startsWith("#") || link.startsWith("?")) name else path
                    if (target.isEmpty() || target.endsWith("/")) target += "index.html"
                    if (target in pages) {
                        if (!fragment.isNullOrEmpty() && fragment !in ids.getValue(target)) {
                            problems += "$name: '$link' points to a missing anchor in $target"
                        }
                    } else if (!isFile(target)) {
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
