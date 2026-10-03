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
    fun check() = verify(moduleName.get(), dependencies.get())

    companion object {
        /**
         * Throws a [GradleException] when [module] declares a forbidden dependency. Each entry of
         * [dependencies] is `kind|target|configuration`, where kind is `project` or `external`.
         */
        fun verify(module: String, dependencies: List<String>) {
            val allowed = ModuleBoundaries.allowed
            // Project dependencies a module may use in test configurations only, for end-to-end tests
            // through the test client. Unlike integration-tests, these modules also have production code.
            val testScopeOnly = mapOf(
                "axiom-validation" to setOf("axiom-test"),
                "axiom-validation-jakarta" to setOf("axiom-test"),
                "axiom-security" to setOf("axiom-test"),
                "axiom-security-jwt" to setOf("axiom-test"),
                "axiom-metrics" to setOf("axiom-test"),
                // Round-trip tests compare generated schemas with the real JSON codec.
                "axiom-openapi" to setOf("axiom-test", "axiom-json")
            )
            val testOnlyModules = ModuleBoundaries.testOnlyModules
            // Third-party production dependencies are confined to the module that adapts them.
            val externalGroups = mapOf(
                "axiom-http" to setOf("io.netty"),
                "axiom-json" to setOf("com.fasterxml.jackson", "com.fasterxml.jackson.core", "com.fasterxml.jackson.datatype"),
                "axiom-validation-jakarta" to setOf("jakarta.validation", "org.hibernate.validator")
            )
            val moduleAllowed = allowed[module]
                ?: throw GradleException(
                    "Module '$module' is not listed in CheckModuleBoundaries; add its allowed project dependencies")
            for (dependency in dependencies) {
                val parts = dependency.split('|')
                val target = parts[1]
                if (parts[0] == "project" && target !in allowed.keys) {
                    throw GradleException("Dependency on unlisted module: $module -> $target")
                }
                if (module in testOnlyModules && !parts[2].startsWith("test")) {
                    throw GradleException("Test-only module '$module' may declare only test dependencies: $target (${parts[2]})")
                }
                if (parts[0] == "project" && target in testOnlyModules) {
                    throw GradleException("No module may depend on test-only module: $module -> $target")
                }
                if (parts[0] == "project" && target !in moduleAllowed
                    && !(parts[2].startsWith("test") && target in testScopeOnly[module].orEmpty())) {
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
}
