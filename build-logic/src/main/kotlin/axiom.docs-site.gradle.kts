// Applied to the root project: builds the documentation site (docs/site.md) into build/site from
// README.md, docs/*.md and the aggregated Javadoc of the published modules, and checks its links.
import java.util.concurrent.Callable

plugins {
    base
    `jvm-toolchains`
}

// `Group/file` entries in display order. The label is each document's own title. A document that
// does not exist is skipped, so a page can be listed before it lands; a document that is not
// listed still gets a page, under "More".
val siteNavigation = listOf(
    "Guide/programming-model.md",
    "Guide/routing.md",
    "Guide/middleware.md",
    "Guide/bodies.md",
    "Guide/validation.md",
    "Guide/errors.md",
    "Guide/streaming.md",
    "Guide/openapi.md",
    "Security and transport/security.md",
    "Security and transport/tls.md",
    "Security and transport/http.md",
    "Operations/observability.md",
    "Operations/admission.md",
    "Operations/execution.md",
    "Project/build.md",
    "Project/releasing.md",
    "Project/benchmarks.md",
    "Project/site.md",
)

val docsPages = tasks.register<BuildDocsSite>("docsPages") {
    group = "documentation"
    description = "Converts README.md and docs/*.md to the HTML pages of the site (build/docs-pages)."
    root = layout.projectDirectory
    markdown.from(layout.projectDirectory.file("README.md"), fileTree("docs") { include("*.md", "CNAME") })
    navigation = siteNavigation
    repository = "https://github.com/matrixjnr/axiom"
    branch = "main"
    outputDir = layout.buildDirectory.dir("docs-pages")
}

fun publishedModules() = subprojects.filter {
    it.pluginManager.hasPlugin("maven-publish") && !it.pluginManager.hasPlugin("java-platform")
}
fun mainSources(module: Project) = module.extensions.getByType<SourceSetContainer>().getByName("main")

// The runtime classpath of every published module (their own jars and everything they use), so the
// Javadoc tool can resolve all referenced types. Resolved from the root project, which is why it is
// a configuration of its own instead of the modules' compile classpaths.
val docsClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    }
}
gradle.projectsEvaluated {
    publishedModules().forEach { dependencies.add(docsClasspath.name, dependencies.project(mapOf("path" to it.path))) }
}

val aggregateJavadoc = tasks.register<Javadoc>("aggregateJavadoc") {
    group = "documentation"
    description = "Generates one Javadoc for the main sources of all published modules (build/docs-api)."
    // Resolved when the task graph is built, after every module is configured.
    setSource(Callable { publishedModules().map { mainSources(it).allJava } })
    // Internal packages are implementation detail, not API.
    exclude("**/internal/**")
    classpath = docsClasspath
    setDestinationDir(layout.buildDirectory.dir("docs-api").get().asFile)
    javadocTool = javaToolchains.javadocToolFor { languageVersion = JavaLanguageVersion.of(21) }
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        docEncoding = "UTF-8"
        charSet = "UTF-8"
        windowTitle = "Axiom API"
        docTitle = "Axiom API"
        addBooleanOption("notimestamp", true)
        addBooleanOption("Xdoclint:none", true)
        quiet()
    }
}

val assembleSite = tasks.register<Sync>("assembleSite") {
    group = "documentation"
    description = "Collects the pages and the aggregated Javadoc in build/site."
    from(docsPages)
    from(aggregateJavadoc) { into("api") }
    into(layout.buildDirectory.dir("site"))
}

// Fast and Javadoc-free, so it is part of `check`: pages only; links into api/ are accepted.
val checkDocsLinks = tasks.register<CheckSiteLinks>("checkDocsLinks") {
    group = "verification"
    description = "Fails when a documentation page links to a missing page, file or anchor."
    siteDir = docsPages.flatMap { it.outputDir }
    verifyApi = false
}
tasks.named("check") { dependsOn(checkDocsLinks) }

val checkSiteLinks = tasks.register<CheckSiteLinks>("checkSiteLinks") {
    group = "verification"
    description = "Fails when the assembled site (pages and Javadoc) has a broken link or anchor."
    siteDir = layout.buildDirectory.dir("site")
    verifyApi = true
    dependsOn(assembleSite)
}

tasks.register("docsSite") {
    group = "documentation"
    description = "Builds the documentation site into build/site and checks its links."
    dependsOn(checkSiteLinks)
}
