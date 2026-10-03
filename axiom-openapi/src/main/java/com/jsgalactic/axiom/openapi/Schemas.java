package com.jsgalactic.axiom.openapi;

import com.jsgalactic.axiom.validation.Constraint;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Builds JSON Schema fragments for Java types and collects the named schemas ("components") they
 * refer to. Records, JavaBean-style classes (including Lombok-generated ones) and enums become
 * named schemas; everything else is inlined. A named schema is registered before its properties
 * are visited, so a type that refers to itself, directly or through others, yields a reference
 * instead of recursing. Unsupported types fail with {@link IllegalArgumentException} naming the
 * type and where it was found.
 */
final class Schemas {
    /** A resolved type: a class and its resolved type arguments. Arrays use {@link #ARRAY}. */
    private record Ref(Class<?> raw, List<Ref> args) {
        String label() {
            if (raw == ARRAY) {
                return args.get(0).label() + "[]";
            }
            return args.isEmpty() ? raw.getName()
                    : raw.getName() + args.stream().map(Ref::label).toList();
        }
    }

    private record Prop(String name, Type type, boolean primitive, boolean required) { }

    private static final Class<?> ARRAY = Object[].class;
    private static final String REF_PREFIX = "#/components/schemas/";

    private final int maxDepth;
    private final Map<Class<?>, String> names;
    private final Map<Class<?>, Map<String, List<Constraint>>> rules;
    private final TreeMap<String, Map<String, Object>> components = new TreeMap<>();
    private final Map<String, Ref> owners = new HashMap<>();

    Schemas(int maxDepth, Map<Class<?>, String> names, Map<Class<?>, Map<String, List<Constraint>>> rules) {
        this.maxDepth = maxDepth;
        this.names = names;
        this.rules = rules;
    }

    /** The named schemas collected so far, sorted by name. */
    Map<String, Map<String, Object>> components() { return components; }

    /** Returns the schema of a body or response type; records, beans and enums are references. */
    Map<String, Object> schema(Type type, String where) {
        return generate(ref(type, Map.of(), where), where, 0, false);
    }

    /** Returns the schema of a parameter: a scalar, an enum (inlined) or a list or set of those. */
    Map<String, Object> simple(Type type, String where) {
        return generate(ref(type, Map.of(), where), where, 0, true);
    }

    // ---- type resolution

    private Ref ref(Type type, Map<TypeVariable<?>, Ref> bindings, String where) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                var component = c.getComponentType();
                return component == byte.class ? new Ref(c, List.of())
                        : new Ref(ARRAY, List.of(ref(component, bindings, where)));
            }
            if (c.getTypeParameters().length > 0) {
                throw fail(where, "the generic type " + c.getSimpleName()
                        + " is used without type arguments; describe it with a ParameterizedType");
            }
            return new Ref(c, List.of());
        }
        if (type instanceof ParameterizedType p) {
            var args = new ArrayList<Ref>();
            for (var argument : p.getActualTypeArguments()) {
                args.add(ref(argument, bindings, where));
            }
            return new Ref((Class<?>) p.getRawType(), List.copyOf(args));
        }
        if (type instanceof GenericArrayType a) {
            return new Ref(ARRAY, List.of(ref(a.getGenericComponentType(), bindings, where)));
        }
        if (type instanceof TypeVariable<?> v) {
            var bound = bindings.get(v);
            if (bound == null) {
                throw fail(where, "the type variable " + v.getName() + " is not bound to a type");
            }
            return bound;
        }
        if (type instanceof WildcardType w) {
            var upper = w.getUpperBounds()[0];
            if (upper == Object.class) {
                throw fail(where, "an unbounded wildcard has no schema");
            }
            return ref(upper, bindings, where);
        }
        throw fail(where, "the type " + type.getTypeName() + " is not supported");
    }

    // ---- generation

    private Map<String, Object> generate(Ref r, String where, int depth, boolean simple) {
        if (depth > maxDepth) {
            throw fail(where, "types are nested deeper than " + maxDepth + " levels; raise Builder.maxDepth if intended");
        }
        var raw = r.raw();
        var scalar = scalar(raw);
        if (scalar != null) {
            return scalar;
        }
        if (raw == byte[].class) {
            return object("type", "string", "format", "byte");
        }
        if (raw == ARRAY) {
            return array(generate(r.args().get(0), where + "[]", depth + 1, simple), false);
        }
        if (raw == Optional.class) {
            requireArgs(r, 1, where);
            return generate(r.args().get(0), where, depth, simple);
        }
        if (raw == Iterable.class || Collection.class.isAssignableFrom(raw)) {
            requireArgs(r, 1, where);
            return array(generate(r.args().get(0), where + "[]", depth + 1, simple), java.util.Set.class.isAssignableFrom(raw));
        }
        if (Map.class.isAssignableFrom(raw)) {
            if (simple) {
                throw fail(where, "a parameter must be a scalar, an enum or a list of those, not a map");
            }
            requireArgs(r, 2, where);
            if (r.args().get(0).raw() != String.class) {
                throw fail(where, "map keys must be String, not " + r.args().get(0).raw().getSimpleName());
            }
            var schema = object("type", "object");
            schema.put("additionalProperties", generate(r.args().get(1), where + "{}", depth + 1, false));
            return schema;
        }
        if (raw.isEnum()) {
            if (simple) {
                return enumSchema(raw);
            }
            return component(r, where, depth);
        }
        rejectUnsupported(raw, where);
        if (simple) {
            throw fail(where, "a parameter must be a scalar, an enum or a list of those, not " + raw.getSimpleName());
        }
        return component(r, where, depth);
    }

    private void rejectUnsupported(Class<?> raw, String where) {
        var name = raw.getName();
        if (raw == Object.class || raw.isInterface() || Modifier.isAbstract(raw.getModifiers()) || raw.isAnnotation()
                || raw.isPrimitive() || name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || raw.isAnonymousClass()) {
            throw fail(where, (raw.getSimpleName().isEmpty() ? name : raw.getSimpleName())
                    + " is not a supported type; use a record, a concrete JavaBean class, an enum, a collection,"
                    + " a map with String keys or a scalar");
        }
    }

    private static void requireArgs(Ref r, int count, String where) {
        if (r.args().size() != count) {
            throw fail(where, r.raw().getSimpleName() + " needs " + count + " type argument(s); describe it with a ParameterizedType");
        }
    }

    private static Map<String, Object> scalar(Class<?> c) {
        if (c == boolean.class || c == Boolean.class) {
            return object("type", "boolean");
        }
        if (c == byte.class || c == Byte.class || c == short.class || c == Short.class || c == int.class
                || c == Integer.class || c == OptionalInt.class) {
            return object("type", "integer", "format", "int32");
        }
        if (c == long.class || c == Long.class || c == OptionalLong.class) {
            return object("type", "integer", "format", "int64");
        }
        if (c == BigInteger.class) {
            return object("type", "integer");
        }
        if (c == float.class || c == Float.class) {
            return object("type", "number", "format", "float");
        }
        if (c == double.class || c == Double.class || c == OptionalDouble.class) {
            return object("type", "number", "format", "double");
        }
        if (c == BigDecimal.class) {
            return object("type", "number");
        }
        if (c == char.class || c == Character.class) {
            var schema = object("type", "string");
            schema.put("minLength", 1);
            schema.put("maxLength", 1);
            return schema;
        }
        if (c == String.class || c == CharSequence.class || c == LocalTime.class || c == LocalDateTime.class) {
            return object("type", "string");
        }
        if (c == UUID.class) {
            return object("type", "string", "format", "uuid");
        }
        if (c == Instant.class || c == OffsetDateTime.class || c == ZonedDateTime.class || c == Date.class) {
            return object("type", "string", "format", "date-time");
        }
        if (c == LocalDate.class) {
            return object("type", "string", "format", "date");
        }
        if (c == OffsetTime.class) {
            return object("type", "string", "format", "time");
        }
        if (c == Duration.class) {
            return object("type", "string", "format", "duration");
        }
        if (c == URI.class) {
            return object("type", "string", "format", "uri");
        }
        return null;
    }

    private static Map<String, Object> array(Map<String, Object> items, boolean unique) {
        var schema = object("type", "array");
        schema.put("items", items);
        if (unique) {
            schema.put("uniqueItems", true);
        }
        return schema;
    }

    private static Map<String, Object> enumSchema(Class<?> type) {
        var values = new ArrayList<Object>();
        for (var constant : type.getEnumConstants()) {
            values.add(((Enum<?>) constant).name());
        }
        var schema = object("type", "string");
        schema.put("enum", values);
        return schema;
    }

    static Map<String, Object> object(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ---- named schemas

    private Map<String, Object> component(Ref r, String where, int depth) {
        var name = nameOf(r);
        var owner = owners.get(name);
        if (owner != null) {
            if (!owner.equals(r)) {
                throw fail(where, "both " + owner.label() + " and " + r.label() + " would be named '" + name
                        + "'; give one a different name with Builder.schemaName");
            }
            return object("$ref", REF_PREFIX + name);
        }
        var schema = new LinkedHashMap<String, Object>();
        components.put(name, schema);
        owners.put(name, r);
        var raw = r.raw();
        if (raw.isEnum()) {
            schema.putAll(enumSchema(raw));
        } else {
            fillObject(schema, r, name, depth);
        }
        return object("$ref", REF_PREFIX + name);
    }

    private String nameOf(Ref r) {
        var base = names.get(r.raw());
        if (base == null) {
            base = r.raw().getSimpleName();
        }
        var out = new StringBuilder(base);
        if (!r.args().isEmpty()) {
            out.append("Of");
            var first = true;
            for (var arg : r.args()) {
                out.append(first ? "" : "And").append(shortName(arg));
                first = false;
            }
        }
        var name = out.toString().replaceAll("[^A-Za-z0-9._-]", "");
        if (name.isEmpty()) {
            throw fail(r.label(), "no usable schema name; give the type one with Builder.schemaName");
        }
        return name;
    }

    private String shortName(Ref r) {
        if (r.raw() == ARRAY) {
            return "ListOf" + shortName(r.args().get(0));
        }
        var base = names.getOrDefault(r.raw(), r.raw().getSimpleName());
        if (r.raw() == byte[].class) {
            base = "Bytes";
        }
        if (r.args().isEmpty()) {
            return base;
        }
        var parts = new ArrayList<String>();
        r.args().forEach(arg -> parts.add(shortName(arg)));
        return base + "Of" + String.join("And", parts);
    }

    private void fillObject(Map<String, Object> schema, Ref r, String name, int depth) {
        var raw = r.raw();
        if (r.args().size() != raw.getTypeParameters().length) {
            throw fail(name, raw.getSimpleName() + " is generic; describe it with a ParameterizedType");
        }
        var bindings = new HashMap<TypeVariable<?>, Ref>();
        for (int i = 0; i < r.args().size(); i++) {
            bindings.put(raw.getTypeParameters()[i], r.args().get(i));
        }
        var props = raw.isRecord() ? recordProps(raw) : beanProps(raw);
        var properties = new LinkedHashMap<String, Map<String, Object>>();
        var required = new LinkedHashSet<String>();
        for (var prop : props) {
            var where = name + "." + prop.name();
            var propRef = ref(prop.type(), bindings, where);
            properties.put(prop.name(), generate(propRef, where, depth + 1, false));
            if (prop.primitive() || prop.required()) {
                required.add(prop.name());
            }
        }
        applyRules(raw, name, properties, required);
        schema.put("type", "object");
        if (!properties.isEmpty()) {
            schema.put("properties", new LinkedHashMap<String, Object>(properties));
        }
        var ordered = new ArrayList<Object>();
        for (var key : properties.keySet()) {
            if (required.contains(key)) {
                ordered.add(key);
            }
        }
        if (!ordered.isEmpty()) {
            schema.put("required", ordered);
        }
    }

    // ---- properties

    private List<Prop> recordProps(Class<?> type) {
        var props = new ArrayList<Prop>();
        for (var component : type.getRecordComponents()) {
            var elements = new ArrayList<AnnotatedElement>();
            elements.add(component.getAccessor());
            try {
                elements.add(type.getDeclaredField(component.getName()));
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
            var json = JacksonNames.read(elements);
            if (json.ignored()) {
                continue;
            }
            var generic = component.getGenericType();
            props.add(new Prop(json.name() != null ? json.name() : component.getName(), generic,
                    generic instanceof Class<?> c && c.isPrimitive(), json.required()));
        }
        return props;
    }

    private static final class Accessors {
        Method getter;
        Method setter;
        Field field;
    }

    private List<Prop> beanProps(Class<?> type) {
        var found = new TreeMap<String, Accessors>();
        for (var m : type.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.isSynthetic()
                    || m.getDeclaringClass() == Object.class || m.getDeclaringClass().getName().startsWith("java.")) {
                continue;
            }
            var n = m.getName();
            if (m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                if (n.startsWith("get") && n.length() > 3) {
                    found.computeIfAbsent(mangle(n.substring(3)), k -> new Accessors()).getter = m;
                } else if (n.startsWith("is") && n.length() > 2
                        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                    found.computeIfAbsent(mangle(n.substring(2)), k -> new Accessors()).getter = m;
                }
            } else if (m.getParameterCount() == 1 && n.startsWith("set") && n.length() > 3) {
                found.computeIfAbsent(mangle(n.substring(3)), k -> new Accessors()).setter = m;
            }
        }
        for (var f : type.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers())) {
                found.computeIfAbsent(f.getName(), k -> new Accessors()).field = f;
            }
        }
        for (Class<?> k = type; k != null && k != Object.class && !k.getName().startsWith("java."); k = k.getSuperclass()) {
            for (var f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                var accessors = found.get(f.getName());
                if (accessors != null && accessors.field == null) {
                    accessors.field = f;
                } else if (accessors == null && JacksonNames.read(List.of(f)).explicit()) {
                    found.computeIfAbsent(f.getName(), x -> new Accessors()).field = f;
                }
            }
        }
        var props = new ArrayList<Prop>();
        found.forEach((implicit, a) -> {
            var elements = new ArrayList<AnnotatedElement>();
            if (a.getter != null) {
                elements.add(a.getter);
            }
            if (a.field != null) {
                elements.add(a.field);
            }
            if (a.setter != null) {
                elements.add(a.setter);
            }
            var json = JacksonNames.read(elements);
            if (json.ignored()) {
                return;
            }
            var generic = a.getter != null ? a.getter.getGenericReturnType()
                    : a.field != null ? a.field.getGenericType() : a.setter.getGenericParameterTypes()[0];
            props.add(new Prop(json.name() != null ? json.name() : implicit, generic,
                    generic instanceof Class<?> c && c.isPrimitive() && a.getter != null, json.required()));
        });
        props.sort(Comparator.comparing(Prop::name));
        var seen = new LinkedHashSet<String>();
        for (var prop : props) {
            if (!seen.add(prop.name())) {
                throw fail(type.getSimpleName(), "two properties are named '" + prop.name() + "'");
            }
        }
        return props;
    }

    /** Jackson's default (legacy) property naming: leading upper-case letters become lower case. */
    static String mangle(String base) {
        var chars = base.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            var lower = Character.toLowerCase(chars[i]);
            if (lower == chars[i]) {
                break;
            }
            chars[i] = lower;
        }
        return new String(chars);
    }

    // ---- validation rules

    private void applyRules(Class<?> raw, String name, Map<String, Map<String, Object>> properties,
                            java.util.Set<String> required) {
        var described = rules.get(raw);
        if (described == null) {
            return;
        }
        described.forEach((key, constraints) -> {
            var element = key.endsWith("[]");
            var property = element ? key.substring(0, key.length() - 2) : key;
            var schema = properties.get(property);
            if (schema == null) {
                throw fail(name, "the rule set names a property '" + property + "' that the schema does not have");
            }
            var target = schema;
            if (element) {
                if (!(schema.get("items") instanceof Map<?, ?>)) {
                    throw fail(name + "." + property, "element rules need a list or array property");
                }
                @SuppressWarnings("unchecked")
                var items = (Map<String, Object>) schema.get("items");
                target = items;
            }
            for (var constraint : constraints) {
                apply(name + "." + key, target, constraint, element ? null : property, required);
            }
        });
    }

    private void apply(String where, Map<String, Object> schema, Constraint c, String property,
                       java.util.Set<String> required) {
        var type = (String) schema.get("type");
        switch (c.kind()) {
            case NOT_NULL -> {
                if (property != null) {
                    required.add(property);
                }
            }
            case NOT_BLANK -> {
                requireType(where, c, type, "string");
                tighten(schema, "minLength", 1, true);
            }
            case NOT_EMPTY -> {
                switch (type == null ? "" : type) {
                    case "string" -> tighten(schema, "minLength", 1, true);
                    case "array" -> tighten(schema, "minItems", 1, true);
                    case "object" -> tighten(schema, "minProperties", 1, true);
                    default -> throw unfit(where, c, type);
                }
            }
            case LENGTH -> {
                requireType(where, c, type, "string");
                bounds(schema, "minLength", "maxLength", c.min(), c.max(), 0, Integer.MAX_VALUE);
            }
            case SIZE -> {
                requireType(where, c, type, "array");
                bounds(schema, "minItems", "maxItems", c.min(), c.max(), 0, Integer.MAX_VALUE);
            }
            case RANGE -> {
                if (!"integer".equals(type) && !"number".equals(type)) {
                    throw unfit(where, c, type);
                }
                bounds(schema, "minimum", "maximum", c.min(), c.max(), Long.MIN_VALUE, Long.MAX_VALUE);
            }
            case PATTERN -> {
                requireType(where, c, type, "string");
                schema.put("pattern", c.pattern());
            }
            case EMAIL -> {
                requireType(where, c, type, "string");
                schema.put("format", "email");
            }
            case ONE_OF -> {
                requireType(where, c, type, "string");
                schema.put("enum", new ArrayList<Object>(c.values()));
            }
            default -> throw new IllegalStateException(c.kind().name());
        }
    }

    private static void requireType(String where, Constraint c, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw unfit(where, c, actual);
        }
    }

    private static IllegalArgumentException unfit(String where, Constraint c, String type) {
        return fail(where, "the " + c.kind() + " rule cannot constrain a value of type "
                + (type == null ? "reference (named schema)" : type));
    }

    private static void bounds(Map<String, Object> schema, String minKey, String maxKey, long min, long max,
                               long unboundedMin, long unboundedMax) {
        if (min > unboundedMin) {
            tighten(schema, minKey, min, true);
        }
        if (max < unboundedMax) {
            tighten(schema, maxKey, max, false);
        }
    }

    private static void tighten(Map<String, Object> schema, String key, long value, boolean lower) {
        var existing = schema.get(key);
        if (existing instanceof Number number) {
            var current = number.longValue();
            value = lower ? Math.max(current, value) : Math.min(current, value);
        }
        schema.put(key, value);
    }

    static IllegalArgumentException fail(String where, String message) {
        return new IllegalArgumentException("Cannot describe " + where + ": " + message);
    }
}
