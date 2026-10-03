// Applied to the root project: the inputs of the documentation site (docs/site.md): the Jekyll source
// assembled from README.md, docs/*.md and site/, the aggregated Javadoc of the published modules,
// and the link checks. Jekyll itself runs outside Gradle (Ruby, pinned by site/Gemfile.lock).
import java.util.concurrent.Callable

plugins {
    base
    `jvm-toolchains`
}

val repositoryUrl = "https://github.com/matrixjnr/axiom"
val markdownSources = files(layout.projectDirectory.file("README.md"), fileTree("docs") { include("*.md", "CNAME") })

val jekyllSource = tasks.register<PrepareJekyllSource>("jekyllSource") {
    group = "documentation"
    description = "Assembles the Jekyll source of the site from site/, README.md and docs/*.md (build/jekyll)."
    root = layout.projectDirectory
    sourceFiles.from(markdownSources, fileTree("site"), fileTree("branding"))
    repository = repositoryUrl
    branch = "main"
    version = project.version.toString()
    commit = providers.environmentVariable("GITHUB_SHA")
    outputDir = layout.buildDirectory.dir("jekyll")
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

// Fast and Javadoc-free, so it is part of `check`: the Markdown sources only.
val checkDocsLinks = tasks.register<CheckDocsLinks>("checkDocsLinks") {
    group = "verification"
    description = "Fails when a document links to a missing page, file or anchor, or lacks site front matter."
    root = layout.projectDirectory
    markdown.from(markdownSources)
    repository = repositoryUrl
    branch = "main"
}
tasks.named("check") { dependsOn(checkDocsLinks) }

// Runs after Jekyll has built the site and the Javadoc is in api/ (see docs/site.md and the Docs
// workflow); it is not part of `check` because it needs Ruby.
tasks.register<CheckSiteLinks>("checkSiteLinks") {
    group = "verification"
    description = "Fails when the built site (build/site, Jekyll output plus api/) has a broken link or anchor."
    siteDir = layout.buildDirectory.dir("site")
    // The project address by default; the Docs workflow passes the address GitHub Pages reports so a
    // site served from a custom domain (no base path) is checked against that (docs/site.md).
    siteUrl = providers.gradleProperty("axiom.site.url").orElse("https://matrixjnr.github.io")
        .zip(providers.gradleProperty("axiom.site.baseUrl").orElse("/axiom")) { origin, base -> origin + base }
    baseUrl = providers.gradleProperty("axiom.site.baseUrl").orElse("/axiom")
}
