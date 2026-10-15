import japicmp.cmp.JApiCmpArchive
import japicmp.cmp.JarArchiveComparator
import japicmp.cmp.JarArchiveComparatorOptions
import japicmp.config.Options
import japicmp.model.AccessModifier
import japicmp.output.stdout.StdoutOutputGenerator
import javassist.ClassPool
import java.util.Optional

data class ApiArtifactComparison(val binary: List<String>, val source: List<String>,
                                 val review: List<String>, val details: String) {
    val compatible: Boolean get() = binary.isEmpty() && source.isEmpty() && review.isEmpty()

    fun report(old: ApiArtifactBundle, new: ApiArtifactBundle): String =
        "${old.module}: ${old.version} -> ${new.version}\n" +
            "Binary incompatibilities:\n${binary.joinToString("\n").ifEmpty { "None" }}\n\n" +
            "Source incompatibilities:\n${source.joinToString("\n").ifEmpty { "None" }}\n\n" +
            "Metadata changes requiring review:\n${review.joinToString("\n").ifEmpty { "None" }}\n\n$details"
}

object CompareApiArtifacts {
    // japicmp compatibility overrides use process-global enum state; comparisons must not overlap.
    @Synchronized
    fun compare(old: ApiArtifactBundle, new: ApiArtifactBundle): ApiArtifactComparison {
        val previous = ClassPool.cacheOpenedJarFile
        ClassPool.cacheOpenedJarFile = false
        try {
            return compareWithoutJarCaching(old, new)
        } finally {
            ClassPool.cacheOpenedJarFile = previous
        }
    }

    private fun compareWithoutJarCaching(old: ApiArtifactBundle, new: ApiArtifactBundle): ApiArtifactComparison {
        require(old.module == new.module) { "Cannot compare different modules" }
        val options = Options.newDefault().apply {
            setAccessModifier(AccessModifier.PROTECTED)
            setIncludeSynthetic(true)
            setOutputOnlyModifications(true)
            setReportOnlyFilename(true)
            addExcludeFromArgument(Optional.of("*.internal.*"), false)
            oldArchives = listOf(JApiCmpArchive(old.archive, old.version))
            newArchives = listOf(JApiCmpArchive(new.archive, new.version))
        }
        val comparatorOptions = JarArchiveComparatorOptions.of(options).apply {
            classPathMode = JarArchiveComparatorOptions.ClassPathMode.TWO_SEPARATE_CLASSPATHS
            oldClassPath = old.dependencies.map { it.absolutePath }
            newClassPath = new.dependencies.map { it.absolutePath }
        }
        val classes = JarArchiveComparator(comparatorOptions).compare(options.oldArchives, options.newArchives)
        val binary = classes.filter { !it.isBinaryCompatible }.map { it.fullyQualifiedName }.sorted()
        val source = classes.filter { !it.isSourceCompatible }.map { it.fullyQualifiedName }.sorted()
        val oldMetadata = ApiArtifactMetadata.read(old.archive)
        val newMetadata = ApiArtifactMetadata.read(new.archive)
        val review = oldMetadata.keys.intersect(newMetadata.keys).sorted().filter { oldMetadata[it] != newMetadata[it] }
            .map { "$it\n- ${oldMetadata[it]}\n+ ${newMetadata[it]}" }
        return ApiArtifactComparison(binary, source, review, StdoutOutputGenerator(options, classes).generate())
    }
}
