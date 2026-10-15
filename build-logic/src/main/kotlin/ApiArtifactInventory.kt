import java.io.File

/** Detects missing bundles and whole-module removals, including modules absent from the candidate build. */
object ApiArtifactInventory {
    fun write(manifests: List<File>, destination: File) {
        val entries = manifests.map { file ->
            val properties = file.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
            val module = requireNotNull(properties["module"])
            val version = requireNotNull(properties["version"])
            Triple(module, version, ApiArtifactBundle.sha256(file))
        }
        require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate artifact modules" }
        val versions = entries.map { it.second }.distinct()
        require(versions.size == 1) { "Artifact bundles must have one version identity" }
        destination.parentFile.mkdirs()
        destination.writeText("version=${versions.single()}\n" + entries.sortedBy { it.first }
            .joinToString("\n", postfix = "\n") { "module.${it.first}=${it.third}" })
    }

    fun read(root: File, version: String): Set<String> {
        val file = File(root, "inventory.properties")
        require(file.isFile) { "Missing artifact inventory: $file" }
        val entries = linkedMapOf<String, String>()
        file.readLines().forEach { line ->
            require('=' in line && entries.put(line.substringBefore('='), line.substringAfter('=')) == null) { "Invalid artifact inventory" }
        }
        require(entries["version"] == version) { "Unexpected artifact inventory version" }
        val modules = entries.keys.filter { it.startsWith("module.") }.map { it.removePrefix("module.") }.toSet()
        require(modules.isNotEmpty()) { "Artifact inventory is empty" }
        modules.forEach { module ->
            require(Regex("[A-Za-z0-9_-]+").matches(module)) { "Invalid inventory module: $module" }
            val manifest = File(root, "$module/manifest.properties")
            require(manifest.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Artifact manifest escapes inventory" }
            require(manifest.isFile && ApiArtifactBundle.sha256(manifest) == entries["module.$module"]) { "Missing or changed artifact bundle: $module" }
            ApiArtifactBundle.read(manifest.parentFile, module, version)
        }
        return modules
    }

    fun requireModules(root: File, version: String, currentModules: Set<String>) {
        val removed = read(root, version) - currentModules
        require(removed.isEmpty()) { "Removed published API modules: ${removed.sorted()}" }
    }
}
