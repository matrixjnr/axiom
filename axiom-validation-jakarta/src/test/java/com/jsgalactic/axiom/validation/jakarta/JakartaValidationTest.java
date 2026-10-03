package com.jsgalactic.axiom.validation.jakarta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.jsgalactic.axiom.error.InternalServerErrorException;
import com.jsgalactic.axiom.error.ValidationException;
import com.jsgalactic.axiom.error.Violation;
import com.jsgalactic.axiom.validation.Rule;
import com.jsgalactic.axiom.validation.Rules;
import com.jsgalactic.axiom.validation.Validation;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Address;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Booking;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Comment;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Draft;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Fragile;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Item;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Order;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Period;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Publishing;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class JakartaValidationTest {
    static final JakartaValidation VALIDATION = JakartaValidation.create();

    @AfterAll
    static void close() {
        VALIDATION.close();
    }

    @Test
    void acceptsValidRecords() {
        assertThat(VALIDATION.validate(Fixtures.validOrder())).isEmpty();
        assertThat(Validation.require(VALIDATION, Fixtures.validOrder())).isEqualTo(Fixtures.validOrder());
    }

    @Test
    void mapsRecordComponentConstraintsToFieldsAndAnnotationCodes() {
        var order = new Order("far too long", "not-an-email", new Address("Main", "12345"), List.of(), List.of(),
                Map.of(), new BigDecimal("0.1"), false);
        assertThat(VALIDATION.validate(order)).containsExactly(
                new Violation("accepted", "assert_true"),
                new Violation("customer", "size"),
                new Violation("discount", "decimal_min"),
                new Violation("email", "email"));
        var blank = new Order(" ", null, new Address("Main", "12345"), null, null, null, null, true);
        assertThat(VALIDATION.validate(blank)).containsExactly(new Violation("customer", "not_blank"));
    }

    @Test
    void cascadesIntoNestedRecordsAndCollectionElementsWithIndexedPaths() {
        var items = new ArrayList<Item>();
        items.add(new Item("ABC-1", 1));
        items.add(new Item(" ", 0));
        items.add(null);
        var order = new Order("ada", null, new Address("", "1"), items, Arrays.asList("ok", " "),
                Map.of("secret-key-POISON", " "), null, true);
        assertThat(VALIDATION.validate(order)).containsExactly(
                new Violation("address.postcode", "pattern"),
                new Violation("address.street", "not_blank"),
                new Violation("items[1].quantity", "min"),
                new Violation("items[1].sku", "not_blank"),
                new Violation("items[2]", "not_null"),
                new Violation("labels", "not_blank"),
                new Violation("tags[1]", "not_blank"));
        var missing = new Order("ada", null, null, null, null, null, null, true);
        assertThat(VALIDATION.validate(missing)).containsExactly(new Violation("address", "not_null"));
    }

    @Test
    void reportsClassLevelConstraintsAtTheObjectPath() {
        assertThat(VALIDATION.validate(new Period(5, 1))).containsExactly(new Violation("", "ordered_range"));
        var booking = new Booking(new Period(5, 1), List.of(new Period(1, 2), new Period(3, 2)));
        assertThat(VALIDATION.validate(booking)).containsExactly(
                new Violation("extra[1]", "ordered_range"), new Violation("period", "ordered_range"));
        assertThat(VALIDATION.validate(null)).containsExactly(new Violation("", "not_null"));
    }

    @Test
    void validatesOnlyTheRequestedGroups() {
        var draft = new Draft(" ", " ");
        assertThat(VALIDATION.validate(draft)).containsExactly(new Violation("title", "not_blank"));
        try (var publishing = JakartaValidation.create(Publishing.class)) {
            assertThat(publishing.validate(draft)).containsExactly(new Violation("body", "not_blank"));
        }
        try (var both = JakartaValidation.create(jakarta.validation.groups.Default.class, Publishing.class)) {
            assertThat(both.validate(draft)).hasSize(2);
        }
        assertThatThrownBy(() -> JakartaValidation.create((Class<?>) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void supportsGroupSequencesPassedAsGroups() {
        try (var sequence = JakartaValidation.create(Fixtures.BasicThenCostly.class)) {
            // The sequence stops at the first group that fails, so the costly check is not reached.
            assertThat(sequence.validate(new Fixtures.Account(" ", "x"))).containsExactly(new Violation("name", "not_blank"));
            assertThat(sequence.validate(new Fixtures.Account("ada", "x"))).containsExactly(new Violation("password", "size"));
            assertThat(sequence.validate(new Fixtures.Account("ada", "long enough"))).isEmpty();
        }
    }

    @Test
    void honorsAGroupSequenceThatRedefinesTheDefaultGroup() {
        assertThat(VALIDATION.validate(new Fixtures.Sequenced(" ", "x"))).containsExactly(new Violation("name", "not_blank"));
        assertThat(VALIDATION.validate(new Fixtures.Sequenced("ada", "x"))).containsExactly(new Violation("password", "size"));
        assertThat(VALIDATION.validate(new Fixtures.Sequenced("ada", "long enough"))).isEmpty();
    }

    @Test
    void cascadesThroughValidOnAContainerAndLeavesTheProvidersDeprecationWarning() {
        var records = new ArrayList<java.util.logging.LogRecord>();
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        var root = java.util.logging.Logger.getLogger("");
        root.addHandler(handler);
        try (var fresh = JakartaValidation.create()) {
            var violations = fresh.validate(new Fixtures.Legacy(List.of(new Item(" ", 1))));
            assertThat(violations).containsExactly(new Violation("items[0].sku", "not_blank"));
        } finally {
            root.removeHandler(handler);
        }
        // The provider's deprecation warning is left alone on purpose: it points at the annotation to fix.
        assertThat(records).anySatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.WARNING);
            assertThat(record.getMessage()).startsWith("HV000271").contains("deprecated");
        });
    }

    @Test
    void neverEvaluatesOrEchoesMessagesOrValues() {
        var poison = "1+1}${7*7}#{''.getClass()}POISON";
        var comment = new Comment(poison, "${1+1}");
        var violations = VALIDATION.validate(comment);
        assertThat(violations).containsExactly(new Violation("note", "size"), new Violation("text", "interpolating"));
        assertThat(violations.toString()).doesNotContain("POISON", "49", "2", "getClass", "${", "must not");
        // The messages the provider produced keep their templates verbatim: nothing was evaluated.
        assertThat(VALIDATION.providerMessages(comment)).containsExactlyInAnyOrder(
                "${" + poison + "} " + poison, "{max} ${1+1}");
        assertThat(VALIDATION.providerMessages(new Comment(null, null)))
                .containsExactly("${null} null");
    }

    @Test
    void runsWithoutAnExpressionLanguageImplementation() {
        assertThatThrownBy(() -> Class.forName("jakarta.el.ExpressionFactory"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.glassfish.expressly.ExpressionFactoryImpl"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void turnsProviderFailuresIntoAGeneric500() {
        var failure = catchThrowableOfType(InternalServerErrorException.class,
                () -> VALIDATION.validate(new Fragile("POISON")));
        assertThat(failure.status()).isEqualTo(500);
        assertThat(failure.code()).isEqualTo("internal_server_error");
        assertThat(failure.getMessage()).isEqualTo("500 internal_server_error");
        assertThat(failure.violations()).isEmpty();
        assertThat(failure.getCause()).as("kept for logs only").isNotNull();
        record Misdeclared(@jakarta.validation.constraints.Size(max = 1) Integer number) {}
        assertThatThrownBy(() -> VALIDATION.validate(new Misdeclared(5)))
                .isInstanceOf(InternalServerErrorException.class).hasMessage("500 internal_server_error");
    }

    @Test
    void capsAndOrdersViolationsDeterministically() {
        record Many(List<@jakarta.validation.constraints.NotBlank String> names) {}
        var violations = VALIDATION.validate(new Many(Collections.nCopies(500, "")));
        assertThat(violations).hasSize(Validation.MAX_VIOLATIONS);
        assertThat(violations).isSortedAccordingTo(
                (a, b) -> a.field().equals(b.field()) ? a.code().compareTo(b.code()) : a.field().compareTo(b.field()));
        assertThat(VALIDATION.validate(new Many(Collections.nCopies(500, "")))).isEqualTo(violations);
        var failure = catchThrowableOfType(ValidationException.class,
                () -> Validation.require(VALIDATION, new Many(Collections.nCopies(500, ""))));
        assertThat(failure.violations()).hasSize(100);
    }

    @Test
    void combinesWithRulesFromTheCoreModule() {
        var combined = Rules.of(Draft.class).field("title", Draft::title, Rule.maxLength(3)).include(VALIDATION);
        assertThat(combined.validate(new Draft("long title", " ")))
                .containsExactly(new Violation("title", "size"));
        assertThat(combined.validate(new Draft("", " "))).containsExactly(new Violation("title", "not_blank"));
    }

    @Test
    void rejectsUseAfterClose() {
        var closing = JakartaValidation.create();
        closing.close();
        closing.close();
        assertThatThrownBy(() -> closing.validate(Fixtures.validOrder())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void derivesSnakeCaseCodesFromAnnotationNames() {
        assertThat(ConstraintCodes.of(jakarta.validation.constraints.NotBlank.class)).isEqualTo("not_blank");
        assertThat(ConstraintCodes.of(jakarta.validation.constraints.FutureOrPresent.class))
                .isEqualTo("future_or_present");
        assertThat(ConstraintCodes.of(org.hibernate.validator.constraints.URL.class)).isEqualTo("url");
        assertThat(ConstraintCodes.of(Fixtures.URLSafeID.class)).isEqualTo("url_safe_id");
        assertThat(ConstraintCodes.of(jakarta.validation.constraints.Size.class)).isEqualTo("size");
    }
}
