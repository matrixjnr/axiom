import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class CheckModuleBoundaries : DefaultTask() {
    @get:Input abstract val moduleName: Property<String>
    @get:Input abstract val dependencies: ListProperty<String>

    @TaskAction
    fun check() {
        val allowed = mapOf(
            "axiom-core" to emptySet(),
            "axiom-server" to setOf("axiom-core"),
            "axiom-http" to setOf("axiom-core", "axiom-server"),
            "axiom-json" to setOf("axiom-core"),
            "axiom-test" to setOf("axiom-core", "axiom-server", "axiom-http", "axiom-json")
        )
        val module = moduleName.get()
        val moduleAllowed = allowed[module]
            ?: throw GradleException(
                "Module '$module' is not listed in CheckModuleBoundaries; add its allowed project dependencies")
        for (dependency in dependencies.get()) {
            val parts = dependency.split('|')
            val target = parts[1]
            if (parts[0] == "project" && target !in allowed.keys) {
                throw GradleException("Dependency on unlisted module: $module -> $target")
            }
            if (parts[0] == "project" && target !in moduleAllowed) {
                throw GradleException("Forbidden module dependency: $module -> $target")
            }
            if (parts[0] == "external" && module == "axiom-core" && !parts[2].startsWith("test")) {
                throw GradleException("Core must remain free of external production dependencies: $target")
            }
            if (parts[2] == "api" && parts[0] == "external") {
                throw GradleException("External public API dependency needs an architecture decision: $module -> $target")
            }
        }
    }
}
