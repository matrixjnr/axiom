plugins {
    application
    id("axiom.java-test")
}

dependencies { implementation(project(":axiom-http")) }
application { mainClass.set("example.Main") }
