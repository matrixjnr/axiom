import java.io.File

/** Deterministic, reviewable declarations and JVM descriptors from JDK 21's javap. */
object ApiSignatures {
    private val typeName = Regex("(?:class|interface) ([^ <{]+)")

    fun inspect(javap: File, directories: List<File>): String {
        val access = directories.flatMap { root ->
            if (!root.isDirectory) emptyList() else root.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { ApiClassAccess.read(it) }
                .filter { ".internal." !in it.name && !it.name.endsWith("package-info") && it.name != "module-info" }
                .toList()
        }.associateBy { it.name }
        val names = access.keys.sorted()
        val output = names.chunked(20).joinToString("\n") { batch ->
            val command = listOf(javap.absolutePath, "-J-Duser.language=en", "-J-Duser.country=US",
                "-J-Dfile.encoding=UTF-8", "-protected", "-s", "-constants", "-classpath",
                directories.joinToString(File.pathSeparator) { it.absolutePath }) + batch
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(process.waitFor() == 0) { "javap failed:\n$text" }
            text
        }
        return canonicalize(output, access)
    }

    fun canonicalize(output: String, access: Map<String, ApiClassAccess> = emptyMap()): String {
        val types = sortedMapOf<String, Pair<String, List<String>>>()
        val lines = output.lines()
        var index = 0
        while (index < lines.size) {
            var header = lines[index++].trim()
            if (!header.endsWith("{") || typeName.find(header) == null) continue
            val name = typeName.find(header)!!.groupValues[1]
            val metadata = access[name]
            if (metadata != null && metadata.enclosing != null) {
                // javap prints protected nested classes as public; use their source-level flags.
                val visibility = if (metadata.flags and 0x0004 != 0) "protected " else "public "
                val static = if (metadata.flags and 0x0008 != 0) "static " else ""
                header = visibility + static + header.removePrefix("public ").removePrefix("protected ")
            }
            val members = mutableListOf<String>()
            while (index < lines.size && lines[index].trim() != "}") {
                val declaration = lines[index++].trim()
                if (!declaration.startsWith("public ") && !declaration.startsWith("protected ")) continue
                check(index < lines.size && lines[index].trim().startsWith("descriptor: ")) {
                    "Missing JVM descriptor for $name: $declaration"
                }
                members += "  $declaration\n    ${lines[index++].trim()}"
            }
            check(index < lines.size) { "Unclosed javap declaration for $name" }
            index++
            if (metadata?.exported ?: (header.startsWith("public ") || header.startsWith("protected "))) {
                check(types.put(name, header to members.sorted()) == null) { "Duplicate API class: $name" }
            }
        }
        if (access.isNotEmpty()) {
            val exported = access.values.filter { it.exported }.map { it.name }.toSet()
            check(types.keys == exported) { "javap declarations do not match exported class files: ${exported - types.keys}" }
        }
        // A public nested class in a private/package-private enclosing class is not an API.
        fun visible(name: String): Boolean = name in types &&
            (access[name]?.enclosing?.let { visible(it) } ?: true)
        val declarations = types.filterKeys(::visible).values.joinToString("\n\n") { (header, members) ->
            (listOf(header) + members + "}").joinToString("\n")
        }
        return "# Axiom API signatures; generated with JDK 21 javap.\n" +
            if (declarations.isEmpty()) "# No exported declarations.\n" else "$declarations\n"
    }

    fun differences(expected: String, actual: String): String {
        val old = expected.replace("\r\n", "\n").trimEnd().lines()
        val new = actual.trimEnd().lines()
        val first = (0 until maxOf(old.size, new.size)).firstOrNull { old.getOrNull(it) != new.getOrNull(it) }
            ?: return ""
        val type = old.take(first + 1).lastOrNull { it.endsWith("{") }.orEmpty()
        return "First difference near line ${first + 1}: $type\n" +
            (old.drop(first).take(6).map { "- $it" } + new.drop(first).take(6).map { "+ $it" }).joinToString("\n")
    }
}
