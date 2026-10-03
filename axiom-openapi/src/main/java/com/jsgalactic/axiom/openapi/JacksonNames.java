package com.jsgalactic.axiom.openapi;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.List;

/**
 * Reads the Jackson annotations that rename or hide a property, by name and reflection, so this
 * module needs no Jackson dependency: when the annotations are not on the class path there is
 * nothing to read, and when they are, the JSON codec (which honors them) and the schema agree.
 */
final class JacksonNames {
    private static final String PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty";
    private static final String IGNORE = "com.fasterxml.jackson.annotation.JsonIgnore";

    /**
     * What the annotations on the members of one property say.
     *
     * @param name explicit name, or null
     * @param required whether {@code required = true} was declared
     * @param ignored whether the property is hidden
     * @param explicit whether any {@code JsonProperty} is present
     */
    record Info(String name, boolean required, boolean ignored, boolean explicit) { }

    private JacksonNames() { }

    static Info read(List<? extends AnnotatedElement> members) {
        String name = null;
        var required = false;
        var ignored = false;
        var explicit = false;
        for (var member : members) {
            for (var annotation : member.getAnnotations()) {
                var type = annotation.annotationType().getName();
                if (type.equals(PROPERTY)) {
                    explicit = true;
                    var value = (String) call(annotation, "value");
                    if (name == null && value != null && !value.isEmpty()) {
                        name = value;
                    }
                    required |= Boolean.TRUE.equals(call(annotation, "required"));
                } else if (type.equals(IGNORE) && !Boolean.FALSE.equals(call(annotation, "value"))) {
                    ignored = true;
                }
            }
        }
        return new Info(name, required, ignored && !explicit, explicit);
    }

    private static Object call(Annotation annotation, String method) {
        try {
            return annotation.annotationType().getMethod(method).invoke(annotation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read @" + annotation.annotationType().getSimpleName(), e);
        }
    }
}
