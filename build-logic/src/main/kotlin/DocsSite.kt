import java.io.File

/**
 * Builds the documentation site pages from the repository's Markdown files.
 *
 * `README.md` becomes `index.html` and each `docs/NAME.md` becomes `NAME.html`; all pages sit in
 * one flat directory next to `site.css`, so every link between them is a plain relative file name
 * and works under a project path (`/axiom/`), at a domain root and from a local `file:` preview.
 * Links from a document to a repository file that is not a page become links to that file on
 * GitHub. Nothing is copied: the Markdown stays the single source of truth.
 */
object DocsSite {
    /** One rendered page. */
    class Page(val source: String, val file: String, val title: String, val body: String)

    /** The result of [build]: pages by output file name, and the problems found while converting. */
    class Result(val pages: Map<String, String>, val problems: List<String>)

    /**
     * Converts [documents] (repository-relative path to Markdown text) into pages.
     *
     * [navigation] lists `Group/docs-file.md` entries in display order; entries whose document does
     * not exist are skipped, and documents missing from the list are added under "More". [exists]
     * answers whether a repository-relative path is a file (1), a directory (2) or absent (0).
     */
    fun build(
        documents: Map<String, String>,
        navigation: List<String>,
        repository: String,
        branch: String,
        exists: (String) -> Int,
    ): Result {
        val problems = mutableListOf<String>()
        val outputs = documents.keys.associateWith { outputName(it) }
        val duplicates = outputs.values.groupBy { it }.filterValues { it.size > 1 }.keys
        duplicates.forEach { problems += "two documents produce $it" }
        val pages = documents.map { (source, text) ->
            val renderer = MarkdownHtml { link -> rewrite(source, link, outputs, repository, branch, exists, problems) }
            val body = renderer.render(text)
            val title = renderer.headings.firstOrNull { it.level == 1 }?.text
                ?: if (source == "README.md") "Axiom" else source.substringAfterLast('/').removeSuffix(".md")
            Page(source, outputs.getValue(source), title, body)
        }
        val menu = menu(pages, navigation)
        val html = pages.associate { page -> page.file to template(page, menu, repository, branch) }
        return Result(html, problems)
    }

    fun outputName(source: String): String =
        if (source == "README.md") "index.html" else source.removePrefix("docs/").removeSuffix(".md") + ".html"

    private fun rewrite(
        source: String, link: String, outputs: Map<String, String>, repository: String, branch: String,
        exists: (String) -> Int, problems: MutableList<String>,
    ): String {
        if (link.isEmpty() || link.startsWith("#") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link)) return link
        if (link.startsWith("/")) {
            problems += "$source: link '$link' is site-absolute and would break under a project path"
            return link
        }
        val path = link.substringBefore('#').substringBefore('?')
        val fragment = if (link.contains('#')) "#" + link.substringAfter('#') else ""
        val resolved = normalize(source.substringBeforeLast('/', "") + "/" + path)
        if (resolved == null) {
            problems += "$source: link '$link' leaves the repository"
            return link
        }
        val page = outputs[resolved]
        if (page != null) return page + fragment
        if (resolved.endsWith(".md") && (resolved.startsWith("docs/") && '/' !in resolved.removePrefix("docs/"))) {
            problems += "$source: link '$link' points to $resolved, which does not exist"
            return link
        }
        return when (exists(resolved)) {
            1 -> "$repository/blob/$branch/$resolved$fragment"
            2 -> "$repository/tree/$branch/${resolved.trimEnd('/')}"
            else -> {
                problems += "$source: link '$link' points to $resolved, which does not exist"
                link
            }
        }
    }

    /** Resolves "." and ".." segments of a repository-relative path; null when it leaves the root. */
    fun normalize(path: String): String? {
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private class Group(val name: String, val pages: List<Page>)

    private fun menu(pages: List<Page>, navigation: List<String>): List<Group> {
        val bySource = pages.associateBy { it.source }
        val listed = mutableSetOf<String>()
        val groups = linkedMapOf<String, MutableList<Page>>()
        for (entry in navigation) {
            val group = entry.substringBefore('/')
            val page = bySource["docs/" + entry.substringAfter('/')] ?: continue
            if (listed.add(page.source)) groups.getOrPut(group) { mutableListOf() } += page
        }
        val others = pages.filter { it.source != "README.md" && it.source !in listed }.sortedBy { it.file }
        if (others.isNotEmpty()) groups.getOrPut("More") { mutableListOf() } += others
        return groups.map { (name, list) -> Group(name, list) }
    }

    private fun template(page: Page, menu: List<Group>, repository: String, branch: String): String {
        val e = MarkdownHtml.Companion::escape
        val nav = StringBuilder()
        nav.append("<ul>\n<li><a href=\"index.html\"").append(if (page.file == "index.html") " aria-current=\"page\"" else "")
            .append(">Home</a></li>\n</ul>\n")
        for (group in menu) {
            nav.append("<h2>").append(e(group.name)).append("</h2>\n<ul>\n")
            for (item in group.pages) {
                nav.append("<li><a href=\"").append(item.file).append('"')
                    .append(if (item.file == page.file) " aria-current=\"page\"" else "")
                    .append('>').append(e(item.title)).append("</a></li>\n")
            }
            nav.append("</ul>\n")
        }
        nav.append("<h2>Reference</h2>\n<ul>\n<li><a href=\"api/index.html\">API Javadoc</a></li>\n")
            .append("<li><a href=\"").append(e(repository)).append("\" rel=\"noopener\">Source on GitHub</a></li>\n</ul>\n")
        val title = if (page.file == "index.html") "Axiom" else e(page.title) + " - Axiom"
        return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>$title</title>
<link rel="stylesheet" href="site.css">
</head>
<body>
<a class="skip" href="#content">Skip to content</a>
<header class="top">
<a class="brand" href="index.html">Axiom</a>
<input type="checkbox" id="menu-toggle" class="menu-toggle" aria-label="Show navigation">
<label for="menu-toggle" class="menu-button">Menu</label>
<nav aria-label="Documentation">
$nav</nav>
</header>
<main id="content">
<article>
${page.body}</article>
<footer>
<p><a href="${e(repository)}/blob/$branch/${e(page.source)}" rel="noopener">Edit this page on GitHub</a>
 - generated from <code>${e(page.source)}</code>.</p>
</footer>
</main>
</body>
</html>
"""
    }

    /** Collects the Markdown sources under [root]: `README.md` and `docs/NAME.md`. */
    fun sources(root: File): Map<String, String> {
        val result = sortedMapOf<String, String>()
        File(root, "README.md").takeIf { it.isFile }?.let { result["README.md"] = it.readText() }
        File(root, "docs").listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }
            .forEach { result["docs/" + it.name] = it.readText() }
        return result
    }
}
