import java.io.File

/**
 * Link handling for the documentation site (see docs/site.md). `README.md` and `docs/NAME.md` are
 * written for GitHub: they link to each other by file path (`../README.md`, `routing.md#section`)
 * and to other repository files (`../examples/rest-api`). On the site every page sits in one flat
 * directory (`README.md` becomes `index.md`), so [rewrite] turns a link into the form the site
 * needs and reports every link that cannot be resolved. Links to pages keep the `.md` suffix,
 * which `jekyll-relative-links` resolves to the page URL; links to other repository files become
 * links to them on GitHub.
 */
object DocsLinks {
    /** Where a repository document lands in the site source: `README.md` is the home page. */
    fun pageName(source: String): String = if (source == "README.md") "index.md" else source.removePrefix("docs/")

    /** The Markdown sources under [root]: `README.md` and `docs/NAME.md`, by repository-relative path. */
    fun sources(root: File): Map<String, String> {
        val result = sortedMapOf<String, String>()
        File(root, "README.md").takeIf { it.isFile }?.let { result["README.md"] = it.readText() }
        File(root, "docs").listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }
            .forEach { result["docs/" + it.name] = it.readText() }
        return result
    }

    /** A leading front matter block (delimiters included, or empty) and the rest of [markdown]. */
    fun splitFrontMatter(markdown: String): Pair<String, String> {
        val opening = Regex("\\A---\\r?\\n").find(markdown) ?: return "" to markdown
        val closing = Regex("(?m)^---(?:\\r?\\n|\\z)").find(markdown, opening.range.last + 1)
            ?: return "" to markdown
        val end = closing.range.last + 1
        return markdown.substring(0, end) to markdown.substring(end)
    }

    /** The `key: value` lines of [markdown]'s front matter. */
    fun frontMatter(markdown: String): Map<String, String> =
        splitFrontMatter(markdown).first.lines().drop(1).filter { ':' in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }

    /**
     * Adds to [problems] what is wrong with the front matter of the document at [source]: a
     * `docs/NAME.md` needs a `title`, a `nav_order` and a `parent` (its menu group), and the README
     * none (the site adds the home page's), so that a page cannot go missing from the menu.
     */
    fun validate(source: String, markdown: String, problems: MutableList<String>) {
        val fields = frontMatter(markdown)
        if (source == "README.md") {
            if (fields.isNotEmpty()) problems += "$source: must not have front matter; the site adds the home page's"
            return
        }
        for (key in listOf("title", "nav_order", "parent")) {
            if (key !in fields) problems += "$source: front matter lacks '$key' (see docs/site.md, Add a page)"
        }
    }

    /** GitHub's heading ids of [markdown], with `-1`, `-2` suffixes on repeats. */
    fun anchors(markdown: String): Set<String> {
        val seen = HashMap<String, Int>()
        val ids = linkedSetOf<String>()
        for (line in proseLines(markdown)) {
            val heading = HEADING.matchEntire(line.second) ?: continue
            val base = slug(heading.groupValues[1])
            val n = seen.merge(base, 1, Int::plus)!! - 1
            ids += if (n == 0) base else "$base-$n"
        }
        return ids
    }

    /** GitHub's anchor for the text of a heading: lower case, letters, digits, `-` and `_` only. */
    fun slug(heading: String): String =
        heading.replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
            .trim().lowercase().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.replace(' ', '-')

    /**
     * Rewrites the links of [text] (the document at repository path [source]) for the site and adds
     * a problem to [problems] for each one that cannot be resolved. [pages] maps the repository path
     * of every page to its text (for anchors); [exists] answers whether a repository-relative path is
     * a file (1), a directory (2) or absent (0).
     */
    fun rewrite(
        source: String, text: String, pages: Map<String, String>, repository: String, branch: String,
        exists: (String) -> Int, problems: MutableList<String>,
    ): String {
        val anchors = HashMap<String, Set<String>>()
        fun anchorsOf(path: String) = anchors.getOrPut(path) { anchors(pages.getValue(path)) }
        val out = StringBuilder()
        val fenced = fencedLines(text)
        for ((index, line) in text.lines().withIndex()) {
            if (index > 0) out.append('\n')
            if (index in fenced) {
                out.append(line)
                continue
            }
            // Inline code is masked so that `[x](y)` inside it is left alone.
            val masked = INLINE_CODE.replace(line) { " ".repeat(it.value.length) }
            var last = 0
            for (match in LINK.findAll(masked)) {
                val range = match.groups[1]!!.range
                val link = line.substring(range)
                val target = resolve(source, link, pages, repository, branch, exists, ::anchorsOf, problems)
                out.append(line, last, range.first).append(target)
                last = range.last + 1
            }
            out.append(line, last, line.length)
        }
        return out.toString()
    }

    private fun resolve(
        source: String, link: String, pages: Map<String, String>, repository: String, branch: String,
        exists: (String) -> Int, anchorsOf: (String) -> Set<String>, problems: MutableList<String>,
    ): String {
        if (link.isEmpty() || SCHEME.containsMatchIn(link)) return link
        if (link.startsWith("/")) {
            problems += "$source: link '$link' is site-absolute and would break under a project path"
            return link
        }
        val path = link.substringBefore('#').substringBefore('?')
        val fragment = if (link.contains('#')) link.substringAfter('#') else null
        if (path.isEmpty()) {
            if (!fragment.isNullOrEmpty() && fragment !in anchorsOf(source)) problems += "$source: '$link' points to a missing anchor"
            return link
        }
        val resolved = normalize(source.substringBeforeLast('/', "") + "/" + path)
        if (resolved == null) {
            problems += "$source: link '$link' leaves the repository"
            return link
        }
        val suffix = if (fragment == null) "" else "#$fragment"
        if (resolved in pages) {
            if (!fragment.isNullOrEmpty() && fragment !in anchorsOf(resolved)) problems += "$source: '$link' points to a missing anchor in $resolved"
            return pageName(resolved) + suffix
        }
        return when (exists(resolved)) {
            1 -> "$repository/blob/$branch/$resolved$suffix"
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

    /** Indexes of the lines inside fenced code blocks, fences included. */
    private fun fencedLines(markdown: String): Set<Int> {
        val result = HashSet<Int>()
        var fence: String? = null
        for ((index, line) in markdown.lines().withIndex()) {
            val marker = FENCE.find(line)?.groupValues?.get(1)
            if (fence == null) {
                if (marker != null) {
                    fence = marker
                    result += index
                }
            } else {
                result += index
                if (marker != null && marker.startsWith(fence) && line.trim() == marker) fence = null
            }
        }
        return result
    }

    private fun proseLines(markdown: String): List<Pair<Int, String>> {
        val fenced = fencedLines(markdown)
        return markdown.lines().withIndex().filter { it.index !in fenced }.map { it.index to it.value }
    }

    private val HEADING = Regex("""#{1,6}\s+(.+?)\s*#*\s*""")
    private val FENCE = Regex("""^\s*(`{3,}|~{3,})""")
    private val INLINE_CODE = Regex("""`+[^`]*`+""")
    private val LINK = Regex("""\]\(\s*<?([^)\s>]*)>?(?:\s+"[^"]*")?\s*\)""")
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
}
