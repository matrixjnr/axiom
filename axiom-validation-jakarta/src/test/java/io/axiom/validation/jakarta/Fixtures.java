package io.axiom.validation.jakarta;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Annotated records and custom constraints shared by the adapter tests. */
final class Fixtures {
    private Fixtures() {}

    record Address(@NotBlank String street, @Pattern(regexp = "[0-9]{5}") String postcode) {}

    record Item(@NotBlank String sku, @Min(1) int quantity) {}

    record Order(@NotBlank @Size(max = 10) String customer, @Email String email, @Valid @NotNull Address address,
                 List<@Valid @NotNull Item> items, List<@NotBlank String> tags, Map<String, @NotBlank String> labels,
                 @DecimalMin("0.5") BigDecimal discount, @AssertTrue boolean accepted) {}

    static Order validOrder() {
        return new Order("ada", "ada@example.org", new Address("Main St 1", "12345"),
                List.of(new Item("ABC-1", 2)), List.of("gift"), Map.of("channel", "web"), new BigDecimal("1.0"), true);
    }

    /** Group for checks that apply only before publishing. */
    interface Publishing {}

    record Draft(@NotBlank String title, @NotBlank(groups = Publishing.class) String body) {}

    /** Fails every value and tries to make the provider evaluate expressions in its messages. */
    @Constraint(validatedBy = Interpolating.Check.class)
    @Target({FIELD, METHOD, PARAMETER, TYPE_USE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @interface Interpolating {
        String message() default "${1+1} #{2*3} {jakarta.validation.constraints.NotNull.message} ${validatedValue}";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};

        final class Check implements ConstraintValidator<Interpolating, String> {
            @Override public boolean isValid(String value, ConstraintValidatorContext context) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate("${" + value + "} " + value).addConstraintViolation();
                return false;
            }
        }
    }

    record Comment(@Interpolating String text, @Size(max = 1, message = "{max} ${1+1}") String note) {}

    /** A constraint whose implementation fails with an internal message. */
    @Constraint(validatedBy = Explodes.Check.class)
    @Target({FIELD, METHOD, PARAMETER, TYPE_USE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @interface Explodes {
        String message() default "boom";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};

        final class Check implements ConstraintValidator<Explodes, Object> {
            @Override public boolean isValid(Object value, ConstraintValidatorContext context) {
                throw new IllegalStateException("SECRET internal detail for " + value);
            }
        }
    }

    record Fragile(@Explodes String value) {}

    /** Class-level constraint: start must not be after end. */
    @Constraint(validatedBy = OrderedRange.Check.class)
    @Target({TYPE, ANNOTATION_TYPE})
    @Retention(RUNTIME)
    @interface OrderedRange {
        String message() default "range";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};

        final class Check implements ConstraintValidator<OrderedRange, Period> {
            @Override public boolean isValid(Period value, ConstraintValidatorContext context) {
                return value.start() <= value.end();
            }
        }
    }

    @OrderedRange
    record Period(int start, int end) {}

    record Booking(@NotNull @Valid Period period, List<@Valid Period> extra) {}

    /** Name with an acronym, for code derivation. */
    @Retention(RUNTIME)
    @interface URLSafeID {}
}
