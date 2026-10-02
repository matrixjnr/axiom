package io.axiom.validation;

import io.axiom.error.Violation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An immutable, annotation-free validator built from field rules.
 *
 * <pre>{@code
 * import static io.axiom.validation.Rule.*;
 *
 * static final Validator<CreateUser> CREATE_USER = Rules.of(CreateUser.class)
 *         .field("name", CreateUser::name, notBlank(), maxLength(80))
 *         .field("email", CreateUser::email, notNull(), email())
 *         .field("age", CreateUser::age, range(0, 150))
 *         .nested("address", CreateUser::address, ADDRESS)
 *         .eachNested("items", CreateUser::items, ITEM)
 *         .check("end", user -> !user.end().isBefore(user.start()), "before_start");
 * }</pre>
 *
 * <p>Every method returns a new instance, so partially built rule sets can be shared and extended;
 * instances are thread-safe as long as the supplied accessors, predicates and validators are.
 * Steps run in declaration order. Each field reports at most the first rule it fails. Nested
 * validators and collection elements extend field paths, for example {@code items[2].name}, and
 * {@code null} nested objects, lists and list elements are skipped by {@code nested},
 * {@code each} and {@code eachNested}, so presence is stated with {@link Rule#notNull()}.
 * Validation stops once {@link Validation#MAX_VIOLATIONS} violations are collected. Field names
 * and codes are checked when the rules are defined. Exceptions thrown by accessors, predicates or
 * nested validators propagate unchanged.
 *
 * @param <T> type of value checked
 */
public final class Rules<T> implements Validator<T> {
    private final List<Step<T>> steps;

    private Rules(List<Step<T>> steps) {
        this.steps = steps;
    }

    /**
     * Starts an empty rule set; it accepts every non-null value.
     *
     * @param type type of value checked, used for type inference only
     * @param <T> type of value checked
     * @return empty rule set
     */
    public static <T> Rules<T> of(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return new Rules<>(List.of());
    }

    /**
     * Adds rules for one field, reporting the first rule it fails at {@code name}.
     *
     * @param name property name, see {@link FieldPath#isPropertyName(String)}
     * @param accessor reads the field
     * @param rules rules checked in order
     * @param <V> field type
     * @return extended rule set
     */
    @SafeVarargs
    public final <V> Rules<T> field(String name, Function<? super T, ? extends V> accessor, Rule<? super V>... rules) {
        var path = FieldPath.root().property(name);
        Objects.requireNonNull(accessor, "accessor");
        var copy = new ArrayList<Rule<? super V>>(rules.length);
        for (var rule : rules) {
            copy.add(Objects.requireNonNull(rule, "rule"));
        }
        var checks = List.copyOf(copy);
        return add((value, out) -> out.check(path, accessor.apply(value), checks));
    }

    /**
     * Validates a nested object, prefixing its violations with {@code name}; skips {@code null}.
     *
     * @param name property name
     * @param accessor reads the nested object
     * @param validator validator for the nested object
     * @param <V> nested type
     * @return extended rule set
     */
    public <V> Rules<T> nested(String name, Function<? super T, ? extends V> accessor, Validator<? super V> validator) {
        var path = FieldPath.root().property(name);
        Objects.requireNonNull(accessor, "accessor");
        Objects.requireNonNull(validator, "validator");
        return add((value, out) -> {
            V nested = accessor.apply(value);
            if (nested != null) {
                out.addAll(path, validator.validate(nested));
            }
        });
    }

    /**
     * Applies rules to every element of a list, reporting at {@code name[index]}; skips a
     * {@code null} list but checks {@code null} elements.
     *
     * @param name property name
     * @param accessor reads the list
     * @param rules rules checked in order for each element
     * @param <E> element type
     * @return extended rule set
     */
    @SafeVarargs
    public final <E> Rules<T> each(String name, Function<? super T, ? extends List<? extends E>> accessor,
                                   Rule<? super E>... rules) {
        var path = FieldPath.root().property(name);
        Objects.requireNonNull(accessor, "accessor");
        var copy = new ArrayList<Rule<? super E>>(rules.length);
        for (var rule : rules) {
            copy.add(Objects.requireNonNull(rule, "rule"));
        }
        var checks = List.copyOf(copy);
        return add((value, out) -> {
            var elements = accessor.apply(value);
            if (elements == null) {
                return;
            }
            var index = 0;
            for (E element : elements) {
                if (out.isFull()) {
                    return;
                }
                out.check(path.index(index++), element, checks);
            }
        });
    }

    /**
     * Validates every non-null element of a list, prefixing violations with {@code name[index]};
     * skips a {@code null} list and {@code null} elements.
     *
     * @param name property name
     * @param accessor reads the list
     * @param validator validator for each element
     * @param <E> element type
     * @return extended rule set
     */
    public <E> Rules<T> eachNested(String name, Function<? super T, ? extends List<? extends E>> accessor,
                                   Validator<? super E> validator) {
        var path = FieldPath.root().property(name);
        Objects.requireNonNull(accessor, "accessor");
        Objects.requireNonNull(validator, "validator");
        return add((value, out) -> {
            var elements = accessor.apply(value);
            if (elements == null) {
                return;
            }
            var index = 0;
            for (E element : elements) {
                if (out.isFull()) {
                    return;
                }
                var elementPath = path.index(index++);
                if (element != null) {
                    out.addAll(elementPath, validator.validate(element));
                }
            }
        });
    }

    /**
     * Adds a check of the whole value, reported at {@value FieldPath#ROOT}.
     *
     * @param test predicate returning true for valid values; never called with null
     * @param code violation code
     * @return extended rule set
     */
    public Rules<T> check(Predicate<? super T> test, String code) {
        return check(FieldPath.root(), test, code);
    }

    /**
     * Adds a check of the whole value, such as a cross-field rule, reported at {@code field}.
     *
     * @param field violation field path, for example {@code period.end}
     * @param test predicate returning true for valid values; never called with null
     * @param code violation code
     * @return extended rule set
     */
    public Rules<T> check(String field, Predicate<? super T> test, String code) {
        return check(FieldPath.root().append(field), test, code);
    }

    private Rules<T> check(FieldPath path, Predicate<? super T> test, String code) {
        var rule = Rule.<T>check(test, code);
        return add((value, out) -> out.check(path, value, List.of(rule)));
    }

    /**
     * Runs another validator on the whole value, keeping its violation fields.
     *
     * @param validator validator to include, for example a Jakarta adapter
     * @return extended rule set
     */
    public Rules<T> include(Validator<? super T> validator) {
        Objects.requireNonNull(validator, "validator");
        return add((value, out) -> out.addAll(FieldPath.root(), validator.validate(value)));
    }

    private Rules<T> add(Step<T> step) {
        var next = new ArrayList<Step<T>>(steps.size() + 1);
        next.addAll(steps);
        next.add(step);
        return new Rules<>(List.copyOf(next));
    }

    /**
     * Checks a value; {@code null} is reported as {@code not_null} at {@value FieldPath#ROOT}.
     *
     * @param value value to check
     * @return immutable violations, at most {@link Validation#MAX_VIOLATIONS}
     */
    @Override
    public List<Violation> validate(T value) {
        if (value == null) {
            return List.of(new Violation(FieldPath.ROOT, "not_null"));
        }
        var out = new Collector();
        for (var step : steps) {
            if (out.isFull()) {
                break;
            }
            step.apply(value, out);
        }
        return out.toList();
    }

    @FunctionalInterface
    private interface Step<T> {
        void apply(T value, Collector out);
    }

    private static final class Collector {
        private final LinkedHashSet<Violation> violations = new LinkedHashSet<>();

        boolean isFull() { return violations.size() >= Validation.MAX_VIOLATIONS; }

        <V> void check(FieldPath path, V value, List<? extends Rule<? super V>> rules) {
            for (var rule : rules) {
                if (!rule.test(value)) {
                    add(new Violation(path.toField(), rule.code()));
                    return;
                }
            }
        }

        void addAll(FieldPath base, List<Violation> nested) {
            for (var violation : Validation.checked(nested)) {
                if (isFull()) {
                    return;
                }
                add(new Violation(base.append(violation.field()).toField(), violation.code()));
            }
        }

        private void add(Violation violation) {
            if (!isFull()) {
                violations.add(violation);
            }
        }

        List<Violation> toList() { return List.copyOf(violations); }
    }
}
