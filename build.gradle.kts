plugins { base }

val modules = subprojects.filter { it.buildFile.isFile }
tasks.named("check") { dependsOn(modules.map { "${it.path}:check" }) }
tasks.named("assemble") { dependsOn(modules.map { "${it.path}:assemble" }) }
