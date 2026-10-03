import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a README code block that claims to quote a compiled source differs from it.
 *
 * A block is tied to a source by a marker line directly before its fence:
 * `<!-- snippet: path -->` quotes the whole file, `<!-- snippet: path#name -->` the lines between
 * `// region name` and `// endregion name` (dedented). Paths are relative to the repository root.
 * The sources are compiled and run by the build, so a README example cannot go stale silently.
 */
abstract class CheckReadmeSnippets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val readme: RegularFileProperty

    /** Repository root; marker paths resolve against it. */
    @get:Internal abstract val root: DirectoryProperty

    /** The files the markers may reference (declared so a source change reruns the check). */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val base = root.get().asFile
        val problems = problems(readme.get().asFile.readText()) { path ->
            base.resolve(path).takeIf { it.isFile }?.readText()
        }
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n", prefix = "README snippets differ from their sources:\n"))
        }
    }

    companion object {
        private val marker = Regex("""^<!-- snippet: (\S+?)(?:#(\S+))? -->$""")

        /** Problems found; [source] returns the text of a repository-relative path, or null if missing. */
        fun problems(readme: String, source: (String) -> String?): List<String> {
            val lines = readme.lines()
            val result = mutableListOf<String>()
            var checked = 0
            for ((index, line) in lines.withIndex()) {
                val match = marker.matchEntire(line.trim()) ?: continue
                val path = match.groupValues[1]
                val name = match.groupValues[2]
                val where = "README.md:${index + 1} ($path" + (if (name.isEmpty()) "" else "#$name") + ")"
                checked++
                val fence = lines.getOrNull(index + 1)
                if (fence == null || !fence.startsWith("```")) {
                    result += "  - $where: the marker must be followed directly by a code fence"
                    continue
                }
                val end = (index + 2 until lines.size).firstOrNull { lines[it].startsWith("```") }
                if (end == null) { result += "  - $where: the code fence is not closed"; continue }
                val quoted = lines.subList(index + 2, end)
                val text = source(path)
                if (text == null) { result += "  - $where: the source file does not exist"; continue }
                val expected = if (name.isEmpty()) text.lines() else region(text, name)
                if (expected == null) { result += "  - $where: the source has no region '$name'"; continue }
                if (normalize(expected) != normalize(quoted)) {
                    result += "  - $where: the README block differs from the source; copy the source into the README"
                }
            }
            if (checked == 0) { result += "  - no '<!-- snippet: path -->' marker found in the README" }
            return result
        }

        /** The dedented lines between `// region name` and `// endregion name`, or null. */
        private fun region(text: String, name: String): List<String>? {
            val lines = text.lines()
            val start = lines.indexOfFirst { it.trim() == "// region $name" }
            val end = lines.indexOfFirst { it.trim() == "// endregion $name" }
            if (start < 0 || end < start) return null
            val body = lines.subList(start + 1, end)
            val indent = body.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
            return body.map { if (it.isBlank()) "" else it.substring(indent) }
        }

        private fun normalize(lines: List<String>): List<String> =
            lines.map { it.trimEnd() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
    }
}
