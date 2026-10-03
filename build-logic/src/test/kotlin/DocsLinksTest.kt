import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocsLinksTest {
    private val repo = "https://github.com/o/r"
    private val files = mapOf("LICENSE" to 1, "examples/app" to 2)

    private fun rewrite(source: String, text: String, pages: Map<String, String>, problems: MutableList<String> = mutableListOf()) =
        DocsLinks.rewrite(source, text, pages, repo, "main", { files[it] ?: 0 }, problems)

    private val pages = mapOf(
        "README.md" to "# Home\n## Start here\n",
        "docs/a.md" to "# A\n## Same\n## Same\n## OPTIONS *\n",
        "docs/b.md" to "# B\n",
    )

    @Test
    fun linksBetweenPagesUseTheFlatSiteNames() {
        val problems = mutableListOf<String>()
        val out = rewrite("docs/b.md", "[a](a.md#same-1) [home](../README.md#start-here) [self](#b)", pages, problems)
        assertThat(out).isEqualTo("[a](a.md#same-1) [home](index.md#start-here) [self](#b)")
        assertThat(problems).isEmpty()
        assertThat(rewrite("README.md", "[a](docs/a.md)", pages)).isEqualTo("[a](a.md)")
    }

    @Test
    fun otherRepositoryFilesLinkToGitHub() {
        val out = rewrite("docs/b.md", "[l](../LICENSE) [e](../examples/app/) <https://x.y> [w](https://x.y)", pages)
        assertThat(out).isEqualTo("[l]($repo/blob/main/LICENSE) [e]($repo/tree/main/examples/app) <https://x.y> [w](https://x.y)")
    }

    @Test
    fun missingTargetsAnchorsAndAbsolutePathsAreReported() {
        val problems = mutableListOf<String>()
        rewrite("docs/b.md", "[a](gone.md) [b](a.md#nope) [c](#nope) [d](/x) [e](../../x) [f](../NOPE)", pages, problems)
        assertThat(problems).hasSize(6)
    }

    @Test
    fun codeIsLeftAlone() {
        val problems = mutableListOf<String>()
        val text = "`[x](gone.md)` text\n```java\n[x](gone.md)\n```\n"
        assertThat(rewrite("docs/b.md", text, pages, problems)).isEqualTo(text)
        assertThat(problems).isEmpty()
    }

    @Test
    fun anchorsFollowGitHub() {
        assertThat(DocsLinks.anchors(pages.getValue("docs/a.md"))).containsExactlyInAnyOrder("a", "same", "same-1", "options-")
        assertThat(DocsLinks.anchors("```\n# not a heading\n```\n# `Real` [link](x.md) heading\n")).containsExactly("real-link-heading")
    }

    @Test
    fun frontMatterIsRequiredForDocumentsOnly() {
        val ok = "---\ntitle: T\nparent: G\nnav_order: 1\n---\n\n# T\n"
        val problems = mutableListOf<String>()
        DocsLinks.validate("docs/a.md", ok, problems)
        DocsLinks.validate("README.md", "# Home\n", problems)
        assertThat(problems).isEmpty()
        DocsLinks.validate("docs/a.md", "# T\n", problems)
        DocsLinks.validate("README.md", ok, problems)
        assertThat(problems).hasSize(4)
        assertThat(DocsLinks.splitFrontMatter(ok).second).isEqualTo("\n# T\n")
    }
}
