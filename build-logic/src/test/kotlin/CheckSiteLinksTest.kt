import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CheckSiteLinksTest {
    private fun problems(pages: Map<String, String>, verifyApi: Boolean = false, existing: Set<String> = setOf("site.css")) =
        CheckSiteLinks.problems(pages, verifyApi) { it in existing }

    @Test
    fun validLinksAndAnchorsHaveNoProblems() {
        val pages = mapOf(
            "index.html" to "<a href=\"a.html#s\">x</a><a href=\"#top\">t</a><h1 id=\"top\"></h1><link href=\"site.css\"><a href=\"https://e/x\">e</a>",
            "a.html" to "<h2 id=\"s\"></h2><a href=\"index.html\">h</a>")
        assertThat(problems(pages)).isEmpty()
    }

    @Test
    fun missingFileAnchorAndAbsolutePathAreReported() {
        val pages = mapOf("index.html" to "<a href=\"gone.html\"></a><a href=\"index.html#nope\"></a><a href=\"/x.html\"></a>")
        assertThat(problems(pages)).hasSize(3)
    }

    @Test
    fun duplicateIdsAreReported() {
        assertThat(problems(mapOf("index.html" to "<h1 id=\"a\"></h1><h2 id=\"a\"></h2>"))).hasSize(1)
    }

    @Test
    fun apiLinksAreAcceptedUnlessVerified() {
        val pages = mapOf("index.html" to "<a href=\"api/index.html\"></a>")
        assertThat(problems(pages)).isEmpty()
        assertThat(problems(pages, verifyApi = true)).hasSize(1)
        assertThat(problems(pages, verifyApi = true, existing = setOf("api/index.html"))).isEmpty()
    }

    @Test
    fun entityEscapedLinksAreDecoded() {
        val pages = mapOf("index.html" to "<a href=\"index.html#a&amp;b\"></a><h1 id=\"a&amp;b\"></h1>")
        assertThat(problems(pages)).isEmpty()
    }
}
