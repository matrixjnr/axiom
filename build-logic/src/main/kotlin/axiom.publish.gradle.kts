plugins {
    `maven-publish`
    signing
}

val descriptions = mapOf(
    "axiom" to "Axiom starter: the core API, HTTP server and JSON codec in one dependency.",
    "axiom-core" to "Axiom application contracts, HTTP request/response values and bootstrap SPI.",
    "axiom-server" to "Axiom lifecycle, compiled route dispatch and default runtime provider.",
    "axiom-http" to "Axiom HTTP/1.1 transport.",
    "axiom-json" to "Axiom strict JSON codec built on Jackson.",
    "axiom-test" to "Axiom in-memory test client that runs requests through the real dispatcher.",
    "axiom-validation" to "Axiom validator interface and annotation-free rules reported as 422 field violations.",
    "axiom-validation-jakarta" to "Axiom adapter running Jakarta Validation constraints through Hibernate Validator.",
    "axiom-security" to "Axiom authentication middleware, role and permission policies, trusted proxies, header redaction and security headers.",
    "axiom-security-jwt" to "Axiom strict JWT bearer-token authenticator using only the JDK (HMAC, RSA and ECDSA).",
    "axiom-bom" to "Axiom bill of materials aligning the versions of all Axiom modules."
)
val repositoryUrl = "https://github.com/matrixjnr/axiom"
// The starter module is published as plain "axiom".
val artifact = if (project.name == "axiom-starter") "axiom" else project.name
val signingKey = providers.gradleProperty("signingInMemoryKey")

publishing {
    publications {
        register<MavenPublication>("maven") {
            artifactId = artifact
            from(components[if (plugins.hasPlugin("java-platform")) "javaPlatform" else "java"])
            pom {
                name.set(artifact)
                description.set(descriptions[artifact] ?: "Axiom module ${project.name}.")
                url.set(repositoryUrl)
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set(providers.gradleProperty("axiom.pom.developerId"))
                        name.set(providers.gradleProperty("axiom.pom.developerName"))
                        url.set(providers.gradleProperty("axiom.pom.developerUrl"))
                    }
                }
                scm {
                    url.set(repositoryUrl)
                    connection.set("scm:git:$repositoryUrl.git")
                    developerConnection.set("scm:git:$repositoryUrl.git")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("$repositoryUrl/issues")
                }
            }
        }
    }
    repositories {
        // A file repository inside the build directory, used by the consumer compatibility tests.
        maven {
            name = "compat"
            url = uri(rootProject.layout.buildDirectory.dir("compat-repo"))
        }
        // A remote target is configured from properties only; no credentials live in the repository.
        providers.gradleProperty("axiom.publish.url").orNull?.let { target ->
            maven {
                name = "remote"
                url = uri(target)
                credentials {
                    username = providers.gradleProperty("axiom.publish.username").orNull
                    password = providers.gradleProperty("axiom.publish.password").orNull
                }
            }
        }
    }
}

// Signing activates only when a key is supplied, for example through the
// ORG_GRADLE_PROJECT_signingInMemoryKey environment variable.
if (signingKey.isPresent) {
    signing {
        useInMemoryPgpKeys(
            providers.gradleProperty("signingInMemoryKeyId").orNull,
            signingKey.get(),
            providers.gradleProperty("signingInMemoryKeyPassword").orNull
        )
        sign(publishing.publications["maven"])
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    val remote = repository?.name == "remote"
    val releasing = providers.gradleProperty("axiom.release").map { it.toBoolean() }.orElse(false)
    val signed = providers.gradleProperty("signingInMemoryKey").isPresent
    val developerId = providers.gradleProperty("axiom.pom.developerId").orElse("TODO")
    val developerName = providers.gradleProperty("axiom.pom.developerName").orElse("TODO")
    doFirst {
        if (releasing.get() && remote) {
            if (developerId.get().startsWith("TODO") || developerName.get().startsWith("TODO")) {
                throw GradleException("Replace the axiom.pom.* placeholders in gradle.properties before a release")
            }
            if (!signed) {
                throw GradleException("A release must be signed: provide signingInMemoryKey")
            }
        }
    }
}
