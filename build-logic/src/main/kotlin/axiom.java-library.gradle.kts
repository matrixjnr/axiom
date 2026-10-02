plugins {
    `java-library`
    id("axiom.java-test")
    id("axiom.publish")
}

java {
    withSourcesJar()
    withJavadocJar()
}

val architectureTest = tasks.register<CheckModuleBoundaries>("architectureTest") {
    group = "verification"
    description = "Checks production module dependency boundaries."
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
