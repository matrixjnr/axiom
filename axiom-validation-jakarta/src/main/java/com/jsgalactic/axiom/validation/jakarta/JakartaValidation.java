package com.jsgalactic.axiom.validation.jakarta;

import com.jsgalactic.axiom.error.InternalServerErrorException;
import com.jsgalactic.axiom.error.Violation;
import com.jsgalactic.axiom.validation.FieldPath;
import com.jsgalactic.axiom.validation.Validation;
import com.jsgalactic.axiom.validation.Validator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ExpressionLanguageFeatureLevel;

/**
 * A {@link Validator} backed by Jakarta Validation annotations, using Hibernate Validator.
 *
 * <pre>{@code
 * record CreateUser(@NotBlank @Size(max = 80) String name, @Valid List<@NotNull Item> items) {}
 *
 * static final JakartaValidation VALIDATION = JakartaValidation.create();
 * var user = Validation.require(VALIDATION, command);
 * }</pre>
 *
 * <p>Constraints may be placed on record components, fields and container elements; {@code @Valid}
 * cascades into nested objects and collection elements. Put it on the type argument
 * ({@code List<@Valid Item>}): {@code @Valid List<Item>} also cascades, but the provider logs
 * deprecation warning {@code HV000271}, which is left visible on purpose. Groups and group
 * sequences are passed to {@link #create(Class[])}; a class-level {@code @GroupSequence}
 * redefining the default group is honored. Executable validation (method parameters and
 * return values) is not offered: this validator checks values only. Each constraint violation becomes a
 * {@link Violation} whose field is the property path ({@code items[2].sku}; map keys and set
 * positions are omitted, class-level constraints report the object's path or, for the validated
 * value itself, the empty {@link FieldPath#ROOT}) and whose code is the constraint annotation's simple name in snake case
 * ({@code NotBlank} becomes {@code not_blank}, {@code DecimalMin} becomes {@code decimal_min}).
 * Provider messages and invalid values are never read into violations. Message interpolation is
 * disabled entirely, no Expression Language implementation is used, and {@code META-INF/validation.xml}
 * is ignored, so a template containing {@code ${...}} is never evaluated. Violations are sorted
 * by field and code and capped at {@link Validation#MAX_VIOLATIONS}. A {@code null} value is
 * reported as {@code not_null} on the whole value.
 *
 * <p>Instances are thread-safe and meant to be created once at startup and shared; creation
 * bootstraps the provider and is comparatively expensive. Close the instance when the application
 * stops to release the provider's caches. If the provider fails while validating, for example
 * because a constraint is declared on an unsupported type or a constraint implementation throws,
 * {@link #validate(Object)} throws an {@link InternalServerErrorException} with code
 * {@code internal_server_error}; the provider's exception is attached as its cause for logging and
 * is never sent to clients.
 */
public final class JakartaValidation implements Validator<Object>, AutoCloseable {
    private static final Comparator<Violation> ORDER =
            Comparator.comparing(Violation::field).thenComparing(Violation::code);

    private final ValidatorFactory factory;
    private final jakarta.validation.Validator validator;
    private final Class<?>[] groups;
    private volatile boolean closed;

    private JakartaValidation(Class<?>[] groups) {
        this.groups = groups;
        this.factory = jakarta.validation.Validation.byProvider(HibernateValidator.class)
                .providerResolver(() -> List.of(new HibernateValidator()))
                .configure()
                .ignoreXmlConfiguration()
                .messageInterpolator(new LiteralMessageInterpolator())
                .constraintExpressionLanguageFeatureLevel(ExpressionLanguageFeatureLevel.NONE)
                .customViolationExpressionLanguageFeatureLevel(ExpressionLanguageFeatureLevel.NONE)
                .buildValidatorFactory();
        this.validator = factory.getValidator();
    }

    /**
     * Bootstraps a validator for the given groups.
     *
     * @param groups validation groups to check; none means the default group
     * @return a validator to share and close when the application stops
     * @throws NullPointerException if a group is null
     */
    public static JakartaValidation create(Class<?>... groups) {
        var copy = groups.clone();
        for (var group : copy) {
            Objects.requireNonNull(group, "group");
        }
        return new JakartaValidation(copy);
    }

    /**
     * Validates a value's constraints.
     *
     * @param value value to validate
     * @return immutable violations sorted by field and code, at most {@link Validation#MAX_VIOLATIONS}
     * @throws InternalServerErrorException if the provider fails
     * @throws IllegalStateException if this validator is closed
     */
    @Override
    public List<Violation> validate(Object value) {
        if (closed) {
            throw new IllegalStateException("JakartaValidation is closed");
        }
        if (value == null) {
            return List.of(new Violation(FieldPath.ROOT, "not_null"));
        }
        try {
            var sorted = new TreeSet<>(ORDER);
            for (var violation : validator.validate(value, groups)) {
                sorted.add(new Violation(path(violation).toField(),
                        ConstraintCodes.of(violation.getConstraintDescriptor().getAnnotation().annotationType())));
            }
            var result = new ArrayList<Violation>(Math.min(sorted.size(), Validation.MAX_VIOLATIONS));
            for (var violation : sorted) {
                if (result.size() == Validation.MAX_VIOLATIONS) {
                    break;
                }
                result.add(violation);
            }
            return List.copyOf(result);
        } catch (RuntimeException e) {
            var failure = new InternalServerErrorException();
            failure.initCause(e);
            throw failure;
        }
    }

    /** Returns the raw provider messages, so tests can confirm templates were not evaluated. */
    List<String> providerMessages(Object value) {
        Set<ConstraintViolation<Object>> violations = validator.validate(value, groups);
        return violations.stream().map(ConstraintViolation::getMessage).toList();
    }

    private static FieldPath path(ConstraintViolation<?> violation) {
        var path = FieldPath.root();
        for (var node : violation.getPropertyPath()) {
            if (node.isInIterable() && node.getIndex() != null) {
                path = path.index(node.getIndex());
            }
            var kind = node.getKind();
            if (kind == ElementKind.PROPERTY) {
                if (!FieldPath.isPropertyName(node.getName())) {
                    return path;
                }
                path = path.property(node.getName());
            } else if (kind != ElementKind.BEAN && kind != ElementKind.CONTAINER_ELEMENT) {
                return path;
            }
        }
        return path;
    }

    /** Releases the provider's resources; later calls to {@link #validate(Object)} fail. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            factory.close();
        }
    }
}
