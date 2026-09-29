plugins {
    `java-library`
    id("axiom.java-test")
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
        listOf("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly").flatMap { name ->
            configurations.getByName(name).dependencies.map { dependency ->
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
