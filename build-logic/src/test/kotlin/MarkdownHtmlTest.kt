import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarkdownHtmlTest {
    private fun html(markdown: String, rewrite: (String) -> String = { it }) = MarkdownHtml(rewrite).render(markdown)

    @Test
    fun headingsGetGithubStyleUniqueIds() {
        val renderer = MarkdownHtml()
        val out = renderer.render("# Title\n\n## Error handlers\n\n## Error handlers\n\n### `Code` & more (1)\n")
        assertThat(renderer.headings.map { it.id })
            .containsExactly("title", "error-handlers", "error-handlers-1", "code--more-1")
        assertThat(out).contains("<h2 id=\"error-handlers\">Error handlers<a class=\"anchor\" href=\"#error-handlers\"")
    }

    @Test
    fun paragraphsJoinLinesAndEscapeHtml() {
        assertThat(html("one\ntwo <b>&\n\nnext")).isEqualTo("<p>one\ntwo &lt;b&gt;&amp;</p>\n<p>next</p>\n")
    }

    @Test
    fun fencedCodeIsEscapedAndKeepsLanguage() {
        val out = html("```java\nList<String> a = \"x\";\n```\n")
        assertThat(out).isEqualTo("<pre><code class=\"language-java\">List&lt;String&gt; a = &quot;x&quot;;\n</code></pre>\n")
    }

    @Test
    fun codeFenceContentIsNotInterpreted() {
        assertThat(html("```\n# not a heading\n- not a list\n```\n")).doesNotContain("<h1").doesNotContain("<li>")
    }

    @Test
    fun inlineCodeEmphasisAndLinks() {
        val out = html("A `x<y` and **bold *nested*** and _it_ snake_case_name [t](a.md#f \"T\")")
        assertThat(out).contains("<code>x&lt;y</code>", "<strong>bold <em>nested</em></strong>", "<em>it</em>",
            "snake_case_name", "<a href=\"a.md#f\" title=\"T\">t</a>")
    }

    @Test
    fun starsInsideCodeAndSpacedStarsAreLiteral() {
        val out = html("`a*b` and 2 * 3 * 4")
        assertThat(out).contains("<code>a*b</code>", "2 * 3 * 4").doesNotContain("<em>")
    }

    @Test
    fun badgeImageInsideLink() {
        val out = html("[![Build](https://x/b.svg?branch=main)](https://x/w)")
        assertThat(out).contains("<a href=\"https://x/w\" rel=\"noopener\"><img src=\"https://x/b.svg?branch=main\" alt=\"Build\"></a>")
    }

    @Test
    fun linkDestinationsAreRewrittenAndAttributeEscaped() {
        val out = html("[a](x.md) [b](https://h/?a=1&b=2)") { if (it.endsWith(".md")) "x.html" else it }
        assertThat(out).contains("href=\"x.html\"", "href=\"https://h/?a=1&amp;b=2\"")
    }

    @Test
    fun escapedBracketsAndUnbalancedBracketsAreText() {
        assertThat(html("\\[x] and [a-z] (1)")).isEqualTo("<p>[x] and [a-z] (1)</p>\n")
    }

    @Test
    fun tightAndLooseListsWithNesting() {
        val tight = html("- one\n- two\n  - inner\n  - inner2\n- three\n")
        assertThat(tight).isEqualTo("<ul>\n<li>one</li>\n<li>two\n<ul>\n<li>inner</li>\n<li>inner2</li>\n</ul></li>\n<li>three</li>\n</ul>\n")
        val loose = html("- one\n\n- two\n")
        assertThat(loose).contains("<li><p>one</p></li>")
    }

    @Test
    fun orderedListKeepsStartAndLazyContinuation() {
        val out = html("3. three\ncontinues\n4. four\n")
        assertThat(out).contains("<ol start=\"3\">", "<li>three\ncontinues</li>", "<li>four</li>")
    }

    @Test
    fun listItemsMayContainCodeBlocks() {
        val out = html("1. step\n\n   ```sh\n   run\n   ```\n2. next\n")
        assertThat(out).contains("<pre><code class=\"language-sh\">run\n</code></pre>", "<li><p>step</p>")
    }

    @Test
    fun listInterruptsParagraph() {
        assertThat(html("text\n- a\n- b\n")).startsWith("<p>text</p>\n<ul>")
    }

    @Test
    fun tablesWithAlignmentEscapedPipesAndInlineMarkup() {
        val out = html("| A | B |\n| :--- | ---: |\n| `x` | a \\| b |\n\nafter")
        assertThat(out).contains("<th style=\"text-align:left\">A</th>", "<th style=\"text-align:right\">B</th>",
            "<td style=\"text-align:left\"><code>x</code></td>", "<td style=\"text-align:right\">a | b</td>", "<p>after</p>")
    }

    @Test
    fun blockQuotesNestBlocksAndLazyLines() {
        val out = html("> **Note.** text\n> more\n>\n> - item\n")
        assertThat(out).contains("<blockquote>", "<strong>Note.</strong> text\nmore", "<ul>")
    }

    @Test
    fun commentsAndRulesAreHandled() {
        assertThat(html("<!-- snippet: a -->\n```java\nx\n```\n\n---\n"))
            .isEqualTo("<pre><code class=\"language-java\">x\n</code></pre>\n<hr>\n")
    }

    @Test
    fun rawHtmlIsEscapedNotPassedThrough() {
        assertThat(html("<script>alert(1)</script>")).isEqualTo("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>\n")
    }
}
