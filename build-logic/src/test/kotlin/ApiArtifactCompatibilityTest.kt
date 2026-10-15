import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApiArtifactCompatibilityTest {
    @TempDir lateinit var root: File

    private fun jar(vararg sources: Pair<String, String>, dependencies: List<File> = emptyList()): File {
        val directory = File(root, "compile-${root.listFiles()!!.size}").apply { mkdirs() }
        val files = sources.map { (name, source) -> File(directory, "$name.java").apply { writeText(source) } }
        val classes = File(directory, "classes").apply { mkdirs() }
        val args = listOf("--release", "21", "-d", classes.path) +
            if (dependencies.isEmpty()) emptyList() else listOf("-classpath", dependencies.joinToString(File.pathSeparator))
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, *(args + files.map { it.path }).toTypedArray()) == 0)
        val archive = File(directory, "fixture.jar")
        JarOutputStream(archive.outputStream()).use { output ->
            classes.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                output.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath).apply { time = 0 })
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        return archive
    }

    private fun bundle(archive: File, version: String, dependencies: List<File> = emptyList()): ApiArtifactBundle {
        val directory = File(root, "bundle-${root.listFiles()!!.size}")
        ApiArtifactBundle.capture("fixture", version, archive, dependencies, directory)
        return ApiArtifactBundle.read(directory, "fixture", version)
    }

    private fun compare(old: File, new: File) = CompareApiArtifacts.compare(bundle(old, "1.0.0"), bundle(new, "1.1.0"))

    @Test fun compatibleAdditionsPassAndRemovedMethodsHaveSeparateFindings() {
        val old = jar("Api" to "package fixture; public class Api { public int read() { return 1; } }")
        val addition = jar("Api" to "package fixture; public class Api { public int read() { return 2; } public void extra() {} }")
        assertThat(compare(old, addition).compatible).isTrue()
        val removed = jar("Api" to "package fixture; public class Api {}")
        val result = compare(old, removed)
        assertThat(result.binary).contains("fixture.Api")
        assertThat(result.source).contains("fixture.Api")
    }

    @Test fun inheritedMethodsUseSeparateDependencyVersions() {
        val oldParent = jar("Parent" to "package fixture; public class Parent { public void read() {} }")
        val newParent = jar("Parent" to "package fixture; public class Parent {}")
        val archive = jar("Api" to "package fixture; public class Api extends Parent {}", dependencies = listOf(oldParent))
        val result = CompareApiArtifacts.compare(bundle(archive, "1.0.0", listOf(oldParent)), bundle(archive, "1.1.0", listOf(newParent)))
        assertThat(result.binary).contains("fixture.Api")
        assertThat(result.source).contains("fixture.Api")
    }

    @Test fun preservedCovariantReturnBridgesRemainCompatible() {
        val parent = jar("Parent" to "package fixture; public class Parent { public Number read() { return 1; } }")
        val old = jar("Api" to "package fixture; public class Api extends Parent { public Number read() { return 1; } }", dependencies = listOf(parent))
        val new = jar("Api" to "package fixture; public class Api extends Parent { public Integer read() { return 1; } }", dependencies = listOf(parent))
        val result = CompareApiArtifacts.compare(bundle(old, "1.0.0", listOf(parent)), bundle(new, "1.1.0", listOf(parent)))
        assertThat(result.binary).isEmpty()
        assertThat(result.source).isEmpty()
    }

    @Test fun interfaceEvolutionReportsSourceIncompatibility() {
        val old = jar("Api" to "package fixture; public interface Api { void read(); }")
        val new = jar("Api" to "package fixture; public interface Api { void read(); void added(); }")
        assertThat(compare(old, new).source).contains("fixture.Api")
    }

    @Test fun recordOrderIsReviewedEvenWhenTheConstructorDescriptorStaysTheSame() {
        val old = jar("Api" to "package fixture; public record Api(String first, String last) {}")
        val new = jar("Api" to "package fixture; public record Api(String last, String first) {}")
        assertThat(compare(old, new).review).anyMatch { it.startsWith("fixture/Api metadata") }
    }

    @Test fun sealedPermitsChangesAreReviewed() {
        val old = jar("Api" to "package fixture; public sealed interface Api permits First {} final class First implements Api {}")
        val new = jar("Api" to "package fixture; public sealed interface Api permits Second {} final class Second implements Api {}")
        assertThat(compare(old, new).review).anyMatch { it.startsWith("fixture/Api metadata") }
    }

    @Test fun annotationDefaultsAndTypeUseValuesAreReviewed() {
        val old = jar("Api" to "package fixture; public @interface Api { int value() default 1; }")
        val new = jar("Api" to "package fixture; public @interface Api { int value() default 2; }")
        assertThat(compare(old, new).review).anyMatch { it.contains("value()I") }
        fun annotated(value: String) = jar("Api" to """
            package fixture;
            @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            @interface Mark { String value(); }
            public class Api { public java.util.List<@Mark("$value") String> read() { return null; } }
        """.trimIndent())
        assertThat(compare(annotated("old"), annotated("new")).review).anyMatch { it.contains("read()") }
    }

    @Test fun missingDependenciesFailInsteadOfIgnoringAnIncompleteComparison() {
        val parent = jar("Parent" to "package fixture; public class Parent {}")
        val archive = jar("Api" to "package fixture; public class Api extends Parent {}", dependencies = listOf(parent))
        val original = javassist.ClassPool.cacheOpenedJarFile
        assertThatThrownBy { compare(archive, archive) }.hasMessageContaining("fixture.Parent")
        assertThat(javassist.ClassPool.cacheOpenedJarFile).isEqualTo(original)
    }

    @Test fun bundlesRejectTamperingWrongIdentityAndUnsafeDependencyPaths() {
        val archive = jar("Api" to "package fixture; public class Api {}")
        val bundle = bundle(archive, "1.0.0")
        val directory = bundle.archive.parentFile
        assertThatThrownBy { ApiArtifactBundle.read(directory, "other") }.hasMessageContaining("identity")
        assertThatThrownBy { ApiArtifactBundle.read(directory, "fixture", "2.0.0") }.hasMessageContaining("Expected baseline")
        val manifest = File(directory, "manifest.properties")
        manifest.appendText("dependency.0=../outside.jar\n")
        assertThatThrownBy { ApiArtifactBundle.read(directory, "fixture") }.hasMessageContaining("Invalid artifact path")
        manifest.writeText(manifest.readText().substringBefore("dependency.0="))
        bundle.archive.appendText("tampered")
        assertThatThrownBy { ApiArtifactBundle.read(directory, "fixture") }.hasMessageContaining("Missing or changed artifact")
    }

    @Test fun captureRejectsBaselineOverlapBeforeChangingRetainedFiles() {
        val archive = jar("Api" to "package fixture; public class Api {}")
        val retained = File(root, "retained").apply { mkdirs() }
        val marker = File(retained, "keep.txt").apply { writeText("original") }
        assertThatThrownBy { ApiArtifactBundle.capture("fixture", "1.0.0", archive, emptyList(), retained, retained) }
            .hasMessageContaining("outside the current artifact output")
        assertThat(marker.readText()).isEqualTo("original")
    }

    @Test fun implementationOnlyChangesDoNotAffectCompatibility() {
        val old = jar("Api" to "package fixture.internal; public class Api { public void removed() {} }")
        val new = jar("Api" to "package fixture.internal; public class Api {}")
        assertThat(compare(old, new).compatible).isTrue()
    }

    @Test fun inventoryRejectsMissingBundlesAndPreservesTheRetainedModuleSet() {
        val archive = jar("Api" to "package fixture; public class Api {}")
        val retained = File(root, "inventory")
        val directory = File(retained, "fixture")
        ApiArtifactBundle.capture("fixture", "1.0.0", archive, emptyList(), directory)
        val manifest = File(directory, "manifest.properties")
        ApiArtifactInventory.write(listOf(manifest), File(retained, "inventory.properties"))
        assertThat(ApiArtifactInventory.read(retained, "1.0.0")).containsExactly("fixture")
        assertThatThrownBy { ApiArtifactInventory.requireModules(retained, "1.0.0", emptySet()) }
            .hasMessageContaining("Removed published API modules: [fixture]")
        manifest.appendText("corrupt=true\n")
        assertThatThrownBy { ApiArtifactInventory.read(retained, "1.0.0") }.hasMessageContaining("Missing or changed artifact bundle")
    }

    @Test fun comparisonsCanStartTogetherWithoutLeakingGlobalState() {
        val archive = jar("Api" to "package fixture; public class Api {}")
        val old = bundle(archive, "1.0.0")
        val new = bundle(archive, "1.1.0")
        val original = javassist.ClassPool.cacheOpenedJarFile
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
            val futures = (1..2).map { executor.submit<Boolean> {
                ready.countDown()
                start.await()
                CompareApiArtifacts.compare(old, new).compatible
            } }
            try {
                assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            } finally { start.countDown() }
            futures.forEach { assertThat(it.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue() }
        }
        assertThat(javassist.ClassPool.cacheOpenedJarFile).isEqualTo(original)
    }
}
