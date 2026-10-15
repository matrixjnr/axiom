import java.io.File
import java.security.MessageDigest

/** Retained artifact and its exact dependency closure, with portable paths and SHA-256 checksums. */
data class ApiArtifactBundle(val module: String, val version: String, val archive: File, val dependencies: List<File>) {
    companion object {
        private val identifier = Regex("[A-Za-z0-9._+-]+")
        private val dependencyPath = Regex("dependencies/[a-f0-9]{64}\\.jar")

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun capture(module: String, version: String, archive: File, dependencies: List<File>, destination: File,
                    baselineRoot: File? = null) {
            require(identifier.matches(module) && identifier.matches(version)) { "Module and artifact version must be identifiers" }
            if (baselineRoot != null) {
                val baseline = baselineRoot.canonicalFile.toPath()
                val output = destination.canonicalFile.toPath()
                require(!output.startsWith(baseline) && !baseline.startsWith(output)) {
                    "Retained baseline must be outside the current artifact output: $baselineRoot"
                }
            }
            require(archive.isFile && dependencies.all { it.isFile && it.extension == "jar" }) { "Artifact inputs must be JAR files" }
            // This is the task's generated output directory, never the retained baseline.
            destination.deleteRecursively()
            destination.mkdirs()
            archive.copyTo(File(destination, "artifact.jar"))
            val entries = sortedMapOf("module" to module, "version" to version,
                "sha256.artifact.jar" to sha256(archive))
            dependencies.distinctBy { it.canonicalPath }.forEachIndexed { index, file ->
                val hash = sha256(file)
                val path = "dependencies/$hash.jar"
                val target = File(destination, path)
                target.parentFile.mkdirs()
                if (!target.exists()) file.copyTo(target)
                entries["dependency.$index"] = path
                entries["sha256.$path"] = hash
            }
            File(destination, "manifest.properties").writeText(entries.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        }

        fun read(directory: File, expectedModule: String, expectedVersion: String? = null): ApiArtifactBundle {
            val manifest = File(directory, "manifest.properties")
            require(manifest.isFile) { "Missing retained artifact manifest: $manifest" }
            val entries = linkedMapOf<String, String>()
            manifest.readLines().forEach { line ->
                require('=' in line) { "Invalid artifact manifest entry" }
                val key = line.substringBefore('=')
                require(entries.put(key, line.substringAfter('=')) == null) { "Duplicate artifact manifest key: $key" }
            }
            val module = entries["module"]
            val version = entries["version"]
            require(module == expectedModule && version != null && identifier.matches(version)) { "Unexpected artifact identity in $manifest" }
            require(expectedVersion == null || version == expectedVersion) { "Expected baseline $expectedVersion, found $version" }
            fun verified(path: String): File {
                require(path == "artifact.jar" || dependencyPath.matches(path)) { "Invalid artifact path: $path" }
                val file = File(directory, path)
                require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "Artifact escapes its bundle: $path" }
                require(file.isFile && sha256(file) == entries["sha256.$path"]) { "Missing or changed artifact: $file" }
                return file
            }
            val count = entries.keys.count { it.startsWith("dependency.") }
            val dependencies = (0 until count).map { index -> verified(requireNotNull(entries["dependency.$index"])) }
            return ApiArtifactBundle(expectedModule, version!!, verified("artifact.jar"), dependencies)
        }
    }
}
