// Applied to the root project: compares the published library modules with the BOM
// constraints and the module boundary map, as part of check.
plugins { base }

val publicationCoverage = tasks.register<CheckPublicationCoverage>("checkPublicationCoverage") {
    group = "verification"
    description = "Fails when a published module is missing from the BOM or the module boundary map."
}
tasks.named("check") { dependsOn(publicationCoverage) }

// Values are read after every project is configured, so the check sees the final module set.
gradle.projectsEvaluated {
    val published = subprojects.filter {
        it.pluginManager.hasPlugin("maven-publish") && !it.pluginManager.hasPlugin("java-platform")
    }
    val bom = subprojects.single { it.name == "axiom-bom" }
    publicationCoverage.configure {
        publishedModules.set(published.map { it.name }.toSet())
        bomConstraints.set(bom.configurations.getByName("api").dependencyConstraints.map { it.name }.toSet())
    }
}
