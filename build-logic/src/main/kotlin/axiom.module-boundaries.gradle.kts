// Registers the dependency boundary check for library modules and test-only modules alike.
plugins { base }

val architectureTest = tasks.register<CheckModuleBoundaries>("architectureTest") {
    group = "verification"
    description = "Checks module dependency boundaries."
    moduleName.set(project.name)
    dependencies.set(provider {
        listOf("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly",
            "annotationProcessor", "testImplementation", "testCompileOnly", "testRuntimeOnly", "testAnnotationProcessor").flatMap { name ->
            (configurations.findByName(name)?.dependencies ?: emptyList<Dependency>()).map { dependency ->
                if (dependency is ProjectDependency) {
                    "project|${dependency.path.substringAfterLast(':')}|$name"
                } else {
                    "external|${dependency.group}:${dependency.name}|$name"
                }
            }
        }
    })
}
tasks.named("check") { dependsOn(architectureTest) }
