import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CheckReadmeSnippetsTest {
    private val fence = "```"
    private val files = mapOf(
        "Hello.java" to "class Hello {\n    void run() {}\n}\n",
        "Test.java" to "class T {\n    void t() {\n        // region demo\n        one();\n\n        two();\n        // endregion demo\n    }\n}\n")

    private fun problems(readme: String) = CheckReadmeSnippets.problems(readme) { files[it] }

    @Test
    fun matchingWholeFileAndRegionHaveNoProblems() {
        val readme = "<!-- snippet: Hello.java -->\n${fence}java\nclass Hello {\n    void run() {}\n}\n$fence\n" +
            "<!-- snippet: Test.java#demo -->\n${fence}java\none();\n\ntwo();\n$fence\n"
        assertThat(problems(readme)).isEmpty()
    }

    @Test
    fun divergingBlockIsReported() {
        val readme = "<!-- snippet: Hello.java -->\n${fence}java\nclass Hello {\n    void walk() {}\n}\n$fence\n"
        assertThat(problems(readme)).hasSize(1).first().asString().contains("README.md:1").contains("differs")
    }

    @Test
    fun divergingRegionIsReported() {
        val readme = "<!-- snippet: Test.java#demo -->\n${fence}java\none();\n$fence\n"
        assertThat(problems(readme)).hasSize(1).first().asString().contains("differs")
    }

    @Test
    fun missingFileRegionFenceAndMarkersAreReported() {
        assertThat(problems("<!-- snippet: Gone.java -->\n${fence}java\nx\n$fence\n")).first().asString()
            .contains("does not exist")
        assertThat(problems("<!-- snippet: Test.java#nope -->\n${fence}java\nx\n$fence\n")).first().asString()
            .contains("no region 'nope'")
        assertThat(problems("<!-- snippet: Hello.java -->\ntext\n")).first().asString().contains("code fence")
        assertThat(problems("<!-- snippet: Hello.java -->\n${fence}java\nx\n")).first().asString().contains("not closed")
        assertThat(problems("no markers\n")).first().asString().contains("no '<!-- snippet")
    }
}
