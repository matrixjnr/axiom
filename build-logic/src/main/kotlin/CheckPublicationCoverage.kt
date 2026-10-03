import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when the set of published library modules, the BOM constraints and the boundary map
 * disagree, so a new module cannot be published without being aligned and boundary-checked.
 */
abstract class CheckPublicationCoverage : DefaultTask() {
    /** Project names of library modules that apply the publish convention (the BOM excluded). */
    @get:Input abstract val publishedModules: SetProperty<String>

    /** Project names constrained by the BOM. */
    @get:Input abstract val bomConstraints: SetProperty<String>

    @TaskAction
    fun check() {
        val problems = problems(
            publishedModules.get(), bomConstraints.get(),
            ModuleBoundaries.allowed.keys - ModuleBoundaries.testOnlyModules)
        if (problems.isNotEmpty()) {
            throw GradleException(
                problems.joinToString("\n", prefix = "Published modules, BOM and boundary map disagree:\n"))
        }
    }

    companion object {
        fun problems(published: Set<String>, bom: Set<String>, boundaries: Set<String>): List<String> {
            val result = mutableListOf<String>()
            fun report(modules: Set<String>, message: String) =
                modules.sorted().forEach { result += "  - '$it' $message" }
            report(published - bom, "is published but has no constraint in axiom-bom/build.gradle.kts")
            report(bom - published, "is constrained by axiom-bom but is not a published library module")
            report(published - boundaries, "is published but is missing from the allowed-dependency map in ModuleBoundaries.kt")
            report(boundaries - published, "is in the allowed-dependency map but is not a published library module")
            return result
        }
    }
}
