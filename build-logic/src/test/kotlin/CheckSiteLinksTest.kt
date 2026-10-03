import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CheckSiteLinksTest {
    private val site = "https://h.example/axiom"

    private fun problems(pages: Map<String, String>, existing: Set<String> = setOf("assets/a.css", "api/index.html")) =
        CheckSiteLinks.problems(pages, site, "/axiom") { it in existing }

    @Test
    fun validLinksAnchorsAndDirectoriesHaveNoProblems() {
        val pages = mapOf(
            "index.html" to "<a href=\"/axiom/a.html#s\">x</a><a href=\"#top\">t</a><h1 id=\"top\"></h1>" +
                "<link href=\"/axiom/assets/a.css\"><a href=\"https://e/x\">e</a><a href=\"/axiom/\">h</a><a href=\"/axiom/api/index.html\">j</a>",
            "a.html" to "<h2 id=\"s\"></h2><a href=\"index.html\">h</a><a href=\"$site/api/index.html\">j</a>",
            "d/index.html" to "<a href=\"../a.html#s\">a</a><a href=\"../api/index.html\">j</a>")
        assertThat(problems(pages)).isEmpty()
    }

    @Test
    fun missingFileAnchorAndForeignPathAreReported() {
        val pages = mapOf("index.html" to "<a href=\"/axiom/gone.html\"></a><a href=\"/axiom/index.html#nope\"></a>" +
            "<a href=\"/other/x.html\"></a><a href=\"$site/gone/\"></a>")
        assertThat(problems(pages)).hasSize(4)
    }

    @Test
    fun relativeLinksFromADirectoryResolveAgainstIt() {
        val pages = mapOf("d/index.html" to "<a href=\"api/index.html\"></a>", "index.html" to "")
        assertThat(problems(pages)).hasSize(1)
    }

    @Test
    fun entityEscapedLinksAreDecoded() {
        val pages = mapOf("index.html" to "<a href=\"index.html#a&amp;b\"></a><h1 id=\"a&amp;b\"></h1>")
        assertThat(problems(pages)).isEmpty()
    }
}
