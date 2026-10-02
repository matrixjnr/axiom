plugins {
    `java-library`
    id("axiom.java-test")
    id("axiom.publish")
    id("axiom.module-boundaries")
}

java {
    withSourcesJar()
    withJavadocJar()
}
