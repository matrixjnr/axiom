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
            // The test client stays codec-neutral: tests send raw bodies and never need a serializer.
            "axiom-test" to setOf("axiom-core", "axiom-server", "axiom-http"),
            "axiom-starter" to setOf("axiom-core", "axiom-server", "axiom-http", "axiom-json")
        )
        // Third-party production dependencies are confined to the module that adapts them.
        val externalGroups = mapOf(
            "axiom-http" to setOf("io.netty"),
            "axiom-json" to setOf("com.fasterxml.jackson", "com.fasterxml.jackson.core", "com.fasterxml.jackson.datatype")
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
            if (parts[0] == "external" && !parts[2].startsWith("test")
                && target.substringBefore(':') !in externalGroups[module].orEmpty()) {
                throw GradleException("External production dependency outside its adapter module: $module -> $target")
            }
            if (parts[2] == "api" && parts[0] == "external") {
                throw GradleException("External public API dependency needs an architecture decision: $module -> $target")
            }
        }
    }
}
