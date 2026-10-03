import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CheckPublicationCoverageTest {
    private fun problems(published: Set<String>, bom: Set<String>, boundaries: Set<String>) =
        CheckPublicationCoverage.problems(published, bom, boundaries)

    @Test
    fun agreeingSetsHaveNoProblems() {
        val modules = setOf("axiom-core", "axiom-server")
        assertThat(problems(modules, modules, modules)).isEmpty()
    }

    @Test
    fun publishedModuleMissingFromTheBomIsReported() {
        val result = problems(setOf("a", "b"), setOf("a"), setOf("a", "b"))
        assertThat(result).containsExactly(
            "  - 'b' is published but has no constraint in axiom-bom/build.gradle.kts")
    }

    @Test
    fun publishedModuleMissingFromTheBoundaryMapIsReported() {
        val result = problems(setOf("a", "b"), setOf("a", "b"), setOf("a"))
        assertThat(result).containsExactly(
            "  - 'b' is published but is missing from the allowed-dependency map in ModuleBoundaries.kt")
    }

    @Test
    fun bomConstraintWithoutPublishedModuleIsReported() {
        val result = problems(setOf("a"), setOf("a", "ghost"), setOf("a"))
        assertThat(result).containsExactly(
            "  - 'ghost' is constrained by axiom-bom but is not a published library module")
    }

    @Test
    fun boundaryEntryWithoutPublishedModuleIsReported() {
        val result = problems(setOf("a"), setOf("a"), setOf("a", "ghost"))
        assertThat(result).containsExactly(
            "  - 'ghost' is in the allowed-dependency map but is not a published library module")
    }

    @Test
    fun moduleInOnlyOneSetIsReportedForEachSetItIsMissingFrom() {
        val result = problems(setOf("a", "lonely"), setOf("a"), setOf("a"))
        assertThat(result).containsExactly(
            "  - 'lonely' is published but has no constraint in axiom-bom/build.gradle.kts",
            "  - 'lonely' is published but is missing from the allowed-dependency map in ModuleBoundaries.kt")
    }

    @Test
    fun problemsAreSortedByModuleName() {
        val result = problems(setOf("z", "m", "a"), emptySet(), emptySet())
        assertThat(result.filter { it.contains("no constraint") }.map { it.substringAfter("'").substringBefore("'") })
            .containsExactly("a", "m", "z")
    }

    @Test
    fun testOnlyModulesAreExemptFromThePublicationSets() {
        val testOnly = ModuleBoundaries.testOnlyModules
        assertThat(testOnly).isNotEmpty()
        assertThat(ModuleBoundaries.allowed.keys).containsAll(testOnly)
        // The task passes the map keys minus the test-only modules: published modules match them.
        val boundaries = ModuleBoundaries.allowed.keys - testOnly
        assertThat(problems(boundaries, boundaries, boundaries)).isEmpty()
        // Without the exemption each test-only module would be reported as unpublished.
        assertThat(problems(boundaries, boundaries, ModuleBoundaries.allowed.keys)).hasSize(testOnly.size)
            .allMatch { it.contains("is not a published library module") }
    }

    @Test
    fun everyModuleInTheMapOnlyDependsOnModulesInTheMap() {
        ModuleBoundaries.allowed.forEach { (module, allowed) ->
            assertThat(ModuleBoundaries.allowed.keys).`as`("dependencies of $module").containsAll(allowed)
        }
    }
}
