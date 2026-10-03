import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocsSiteTest {
    private val repo = "https://github.com/o/r"
    private val files = mapOf("LICENSE" to 1, "examples/app" to 2, "CHANGELOG.md" to 1)

    private fun build(documents: Map<String, String>, navigation: List<String> = emptyList()) =
        DocsSite.build(documents, navigation, repo, "main") { files[it] ?: 0 }

    @Test
    fun readmeIsTheIndexAndDocsAreFlatPages() {
        val result = build(mapOf("README.md" to "# Axiom\n\n[go](docs/a.md#x)\n", "docs/a.md" to "# A\n\n## X\n\n[home](../README.md)\n"))
        assertThat(result.problems).isEmpty()
        assertThat(result.pages.keys).containsExactlyInAnyOrder("index.html", "a.html")
        assertThat(result.pages.getValue("index.html")).contains("href=\"a.html#x\"")
        assertThat(result.pages.getValue("a.html")).contains("href=\"index.html\"", "<title>A - Axiom</title>")
    }

    @Test
    fun linksToOtherRepositoryFilesPointAtGithub() {
        val result = build(mapOf("README.md" to "[l](LICENSE) [c](CHANGELOG.md#top) [e](examples/app)\n"))
        assertThat(result.problems).isEmpty()
        assertThat(result.pages.getValue("index.html")).contains(
            "href=\"$repo/blob/main/LICENSE\"", "href=\"$repo/blob/main/CHANGELOG.md#top\"", "href=\"$repo/tree/main/examples/app\"")
    }

    @Test
    fun missingTargetsAndAbsolutePathsAreProblems() {
        val result = build(mapOf("README.md" to "[a](docs/missing.md) [b](nope.txt) [c](/abs) [d](../../x)\n"))
        assertThat(result.problems).hasSize(4)
    }

    @Test
    fun navigationSkipsMissingPagesAndAppendsUnlistedOnes() {
        val docs = mapOf("README.md" to "# Axiom\n", "docs/b.md" to "# Bee\n", "docs/z.md" to "# Zed\n")
        val page = build(docs, listOf("Guide/b.md", "Guide/openapi.md")).pages.getValue("index.html")
        assertThat(page).contains("<h2>Guide</h2>", "href=\"b.html\">Bee", "<h2>More</h2>", "href=\"z.html\">Zed")
        assertThat(page).doesNotContain("openapi")
    }

    @Test
    fun currentPageIsMarkedAndSiteIsRelativeOnly() {
        val pages = build(mapOf("README.md" to "# Axiom\n", "docs/b.md" to "# Bee\n"), listOf("Guide/b.md")).pages
        assertThat(pages.getValue("b.html")).contains("href=\"b.html\" aria-current=\"page\"")
        assertThat(CheckSiteLinks.problems(pages, false) { it == "site.css" }).isEmpty()
        pages.values.forEach { assertThat(it).doesNotContain("href=\"/") }
    }
}
