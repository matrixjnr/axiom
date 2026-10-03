package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class PublicApiTest {
    @Test
    void exportedSignaturesUseOnlyJdkAndPublicAxiomTypes() throws Exception {
        var classes = new HashSet<Class<?>>();
        classes.addAll(classesBeside(Axiom.class));
        classes.addAll(classesBeside(TestClient.class));
        assertThat(classes).contains(Axiom.class, TestClient.class);
        var visited = new HashSet<Type>();
        for (var type : classes) {
            if (!Modifier.isPublic(type.getModifiers()) || type.getName().contains(".internal.")) {
                continue;
            }
            check(type.getGenericSuperclass(), visited);
            for (var parent : type.getGenericInterfaces()) { check(parent, visited); }
            for (var parameter : type.getTypeParameters()) { check(parameter, visited); }
            for (var method : type.getDeclaredMethods()) {
                if (exported(method.getModifiers())) {
                    check(method.getGenericReturnType(), visited);
                    for (var parameter : method.getGenericParameterTypes()) { check(parameter, visited); }
                    for (var exception : method.getGenericExceptionTypes()) { check(exception, visited); }
                    for (var parameter : method.getTypeParameters()) { check(parameter, visited); }
                }
            }
            for (var constructor : type.getDeclaredConstructors()) {
                if (exported(constructor.getModifiers())) {
                    for (var parameter : constructor.getGenericParameterTypes()) { check(parameter, visited); }
                    for (var exception : constructor.getGenericExceptionTypes()) { check(exception, visited); }
                }
            }
            for (var field : type.getDeclaredFields()) {
                if (exported(field.getModifiers())) { check(field.getGenericType(), visited); }
            }
        }
    }

    @Test
    void rejectsThirdPartyAndInternalSignatureTypes() throws Exception {
        assertThatThrownBy(() -> check(org.junit.jupiter.api.Test.class, new HashSet<>()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("third-party");
        var internal = Class.forName("com.jsgalactic.axiom.server.internal.DefaultApplicationProvider");
        assertThatThrownBy(() -> check(internal, new HashSet<>()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("internal");
        var generic = GenericFixture.class.getDeclaredField("callbacks").getGenericType();
        assertThatThrownBy(() -> check(generic, new HashSet<>()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("third-party");
    }

    private static final class GenericFixture {
        java.util.List<? extends org.junit.jupiter.api.Test> callbacks;
    }
    private static boolean exported(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void check(Type type, Set<Type> visited) {
        if (type == null || !visited.add(type)) { return; }
        if (type instanceof Class<?> value) {
            if (value.isArray()) { check(value.getComponentType(), visited); return; }
            if (value.isPrimitive()) { return; }
            assertThat(value.getName()).doesNotContain(".internal.");
            // javax.net.ssl is part of the JDK; TlsOptions accepts an SSLContext from the application.
            assertThat(value.getName().startsWith("java.") || value.getName().startsWith("javax.net.ssl.")
                    || value.getName().startsWith("com.jsgalactic.axiom."))
                    .as("Public signature must not expose third-party type %s", value.getName()).isTrue();
            assertThat(Modifier.isPublic(value.getModifiers()))
                    .as("Public signature type %s must be accessible", value.getName()).isTrue();
        } else if (type instanceof ParameterizedType value) {
            check(value.getRawType(), visited);
            check(value.getOwnerType(), visited);
            for (var argument : value.getActualTypeArguments()) { check(argument, visited); }
        } else if (type instanceof GenericArrayType value) {
            check(value.getGenericComponentType(), visited);
        } else if (type instanceof TypeVariable<?> value) {
            for (var bound : value.getBounds()) { check(bound, visited); }
        } else if (type instanceof WildcardType value) {
            for (var bound : value.getUpperBounds()) { check(bound, visited); }
            for (var bound : value.getLowerBounds()) { check(bound, visited); }
        } else {
            throw new AssertionError("Unhandled signature type: " + type);
        }
    }

    private static Set<Class<?>> classesBeside(Class<?> anchor) throws Exception {
        var location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        var names = new HashSet<String>();
        if (Files.isDirectory(location)) {
            try (var paths = Files.walk(location)) {
                paths.filter(path -> path.toString().endsWith(".class"))
                        .forEach(path -> names.add(location.relativize(path).toString().replace('\\', '/')));
            }
        } else {
            try (var jar = new JarFile(location.toFile())) {
                jar.stream().map(entry -> entry.getName()).filter(name -> name.endsWith(".class"))
                        .forEach(names::add);
            }
        }
        var classes = new HashSet<Class<?>>();
        for (var name : names) {
            if (name.startsWith("com/jsgalactic/axiom/")) {
                classes.add(Class.forName(name.substring(0, name.length() - 6).replace('/', '.'),
                        false, anchor.getClassLoader()));
            }
        }
        return classes;
    }
}
