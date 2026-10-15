import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.TypeAnnotationNode
import java.io.File
import java.util.jar.JarFile

/** Conservative review of declaration metadata, including record order and type-use annotations. */
object ApiArtifactMetadata {
    fun read(archive: File): Map<String, List<String>> {
        val result = sortedMapOf<String, List<String>>()
        JarFile(archive).use { jar ->
            val classes = jar.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }
                .associate { entry -> jar.getInputStream(entry).use { stream ->
                    val cls = ClassNode()
                    ClassReader(stream).accept(cls, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    cls.name to cls
                } }
            fun exported(name: String): Boolean {
                val cls = classes[name] ?: return false
                if ("/internal/" in name || cls.access and 0x0005 == 0) return false
                val inner = cls.innerClasses.find { it.name == name }
                if (inner != null) {
                    if (inner.access and 0x0005 == 0) return false
                    return inner.outerName?.let(::exported) ?: true
                }
                return true
            }
            classes.filterKeys(::exported).forEach { (name, cls) ->
                result["$name metadata"] = annotations(cls.visibleAnnotations, cls.invisibleAnnotations) +
                    typeAnnotations(cls.visibleTypeAnnotations, cls.invisibleTypeAnnotations) +
                    listOf("permits=${cls.permittedSubclasses.orEmpty().sorted()}") +
                    cls.recordComponents.orEmpty().flatMapIndexed { index, component ->
                        listOf("record[$index]=${component.name}:${component.descriptor}:${component.signature}") +
                            annotations(component.visibleAnnotations, component.invisibleAnnotations).map { "record[$index].$it" } +
                            typeAnnotations(component.visibleTypeAnnotations, component.invisibleTypeAnnotations).map { "record[$index].$it" }
                    }
                cls.fields.filter { it.access and 0x0005 != 0 }.forEach {
                    result["$name field ${it.name}:${it.desc}"] = annotations(it.visibleAnnotations, it.invisibleAnnotations) +
                        typeAnnotations(it.visibleTypeAnnotations, it.invisibleTypeAnnotations)
                }
                cls.methods.filter { it.access and 0x0005 != 0 }.forEach { method ->
                    result["$name method ${method.name}${method.desc}"] =
                        annotations(method.visibleAnnotations, method.invisibleAnnotations) +
                        typeAnnotations(method.visibleTypeAnnotations, method.invisibleTypeAnnotations) +
                        listOf("default=${value(method.annotationDefault)}") +
                        listOf(method.visibleParameterAnnotations, method.invisibleParameterAnnotations).flatMapIndexed { kind, parameters ->
                            parameters.orEmpty().flatMapIndexed { index, annotations ->
                                annotations.orEmpty().map { "parameter[$kind,$index]=${annotation(it)}" }.sorted()
                            }
                        }
                }
            }
        }
        return result
    }

    private fun annotations(visible: List<AnnotationNode>?, invisible: List<AnnotationNode>?): List<String> =
        visible.orEmpty().map { "visible=${annotation(it)}" }.sorted() +
            invisible.orEmpty().map { "invisible=${annotation(it)}" }.sorted()

    private fun typeAnnotations(visible: List<TypeAnnotationNode>?, invisible: List<TypeAnnotationNode>?): List<String> =
        visible.orEmpty().map { "visibleType[${it.typeRef},${it.typePath}]=${annotation(it)}" }.sorted() +
            invisible.orEmpty().map { "invisibleType[${it.typeRef},${it.typePath}]=${annotation(it)}" }.sorted()

    private fun annotation(node: AnnotationNode): String = node.desc + node.values.orEmpty().chunked(2)
        .map { "${it[0]}=${value(it[1])}" }.sorted().joinToString(",", "(", ")")

    private fun value(value: Any?): String = when (value) {
        null -> "none"
        is AnnotationNode -> annotation(value)
        is Type -> "type:${value.descriptor}"
        is List<*> -> value.joinToString(",", "[", "]") { value(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { value(it) }
        else -> "${value.javaClass.simpleName}:${value.toString().length}:$value"
    }
}
