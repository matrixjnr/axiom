package com.jsgalactic.axiom.validation;

import static com.jsgalactic.axiom.validation.Rule.email;
import static com.jsgalactic.axiom.validation.Rule.maxLength;
import static com.jsgalactic.axiom.validation.Rule.notBlank;
import static com.jsgalactic.axiom.validation.Rule.notNull;
import static com.jsgalactic.axiom.validation.Rule.pattern;
import static com.jsgalactic.axiom.validation.Rule.range;
import static com.jsgalactic.axiom.validation.Rule.size;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.error.Violation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RulesTest {
    record Address(String street, String postcode) {}
    record Item(String sku, int quantity) {}
    record Order(String customer, String email, Integer priority, Address address, List<Item> items,
                 List<String> tags, int start, int end) {}

    static final Validator<Address> ADDRESS = Rules.of(Address.class)
            .field("street", Address::street, notBlank(), maxLength(40))
            .field("postcode", Address::postcode, notNull(), pattern("[0-9]{5}"));
    static final Validator<Item> ITEM = Rules.of(Item.class)
            .field("sku", Item::sku, notBlank(), pattern("[A-Z]{3}-[0-9]{4}"))
            .field("quantity", Item::quantity, range(1, 99));
    static final Validator<Order> ORDER = Rules.of(Order.class)
            .field("customer", Order::customer, notBlank(), maxLength(20))
            .field("email", Order::email, notNull(), email())
            .field("priority", Order::priority, range(1, 5))
            .field("address", Order::address, notNull())
            .nested("address", Order::address, ADDRESS)
            .field("items", Order::items, notNull(), size(1, 50))
            .eachNested("items", Order::items, ITEM)
            .each("tags", Order::tags, notBlank(), maxLength(10))
            .check("end", order -> order.end() >= order.start(), "before_start")
            .check(order -> !"blocked".equals(order.customer()), "customer_blocked");

    static Order valid() {
        return new Order("ada", "ada@example.org", 3, new Address("Main St 1", "12345"),
                List.of(new Item("ABC-1234", 2)), List.of("gift"), 1, 2);
    }

    @Test
    void acceptsValidValues() {
        assertThat(ORDER.validate(valid())).isEmpty();
    }

    @Test
    void reportsTheFirstFailingRulePerFieldInDeclarationOrder() {
        var order = new Order(" ", null, 9, valid().address(), valid().items(), List.of(), 5, 4);
        assertThat(ORDER.validate(order)).containsExactly(
                new Violation("customer", "not_blank"),
                new Violation("email", "not_null"),
                new Violation("priority", "range"),
                new Violation("end", "before_start"));
    }

    @Test
    void reportsNestedObjectsAndCollectionElementsWithStablePaths() {
        var items = new ArrayList<Item>();
        items.add(new Item("ABC-1234", 1));
        items.add(new Item("ABC-1234", 1));
        items.add(new Item("nope", 0));
        items.add(null);
        var order = new Order("ada", "ada@example.org", null, new Address("", "1"), items,
                Arrays.asList("ok", " ", null, "far-too-long-tag"), 0, 0);
        assertThat(ORDER.validate(order)).containsExactly(
                new Violation("address.street", "not_blank"),
                new Violation("address.postcode", "pattern"),
                new Violation("items[2].sku", "pattern"),
                new Violation("items[2].quantity", "range"),
                new Violation("tags[1]", "not_blank"),
                new Violation("tags[2]", "not_blank"),
                new Violation("tags[3]", "size"));
    }

    @Test
    void skipsAbsentNestedValuesSoPresenceIsAnExplicitRule() {
        var order = new Order("ada", "ada@example.org", null, null, null, null, 0, 0);
        assertThat(ORDER.validate(order)).containsExactly(
                new Violation("address", "not_null"), new Violation("items", "not_null"));
    }

    @Test
    void reportsObjectLevelChecksAndNullValuesOnTheRoot() {
        var blocked = new Order("blocked", "ada@example.org", 1, valid().address(), valid().items(), List.of(), 0, 0);
        assertThat(ORDER.validate(blocked)).containsExactly(new Violation("", "customer_blocked"));
        assertThat(ORDER.validate(null)).containsExactly(new Violation("", "not_null"));
    }

    @Test
    void prefixesViolationsFromAnyNestedValidator() {
        Validator<Address> external = address -> List.of(new Violation("lines[0]", "too_short"),
                new Violation(FieldPath.ROOT, "unknown_region"));
        var rules = Rules.of(Order.class).nested("shipping", Order::address, external)
                .eachNested("items", Order::items, item -> List.of(new Violation(FieldPath.ROOT, "discontinued")));
        assertThat(rules.validate(valid())).containsExactly(
                new Violation("shipping.lines[0]", "too_short"),
                new Violation("shipping", "unknown_region"),
                new Violation("items[0]", "discontinued"));
    }

    @Test
    void composesWithOtherValidatorsAndStaysImmutable() {
        var base = Rules.of(Order.class).field("customer", Order::customer, notBlank());
        var extended = base.field("email", Order::email, notNull());
        var order = new Order("", null, null, null, null, null, 0, 0);
        assertThat(base.validate(order)).containsExactly(new Violation("customer", "not_blank"));
        assertThat(extended.validate(order))
                .containsExactly(new Violation("customer", "not_blank"), new Violation("email", "not_null"));
        var included = Rules.of(Order.class).include(base).include(value -> List.of(new Violation("x", "y")));
        assertThat(included.validate(order)).containsExactly(new Violation("customer", "not_blank"), new Violation("x", "y"));
        assertThatThrownBy(() -> ORDER.validate(order).add(new Violation("a", "b")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnsafeNamesAndCodesWhenRulesAreDefined() {
        var rules = Rules.of(Order.class);
        assertThatThrownBy(() -> rules.field("bad name", Order::customer, notNull()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.check("x", order -> true, "Bad Code")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.check(order -> true, "no spaces allowed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.check("a..b", order -> true, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.field("customer", Order::customer, (Rule<Object>) null))
                .isInstanceOf(NullPointerException.class);
        assertThat(rules.check("period.end", order -> false, "x").validate(valid()))
                .containsExactly(new Violation("period.end", "x"));
    }

    @Test
    void stopsAtTheViolationCap() {
        record Batch(List<String> names) {}
        var calls = new int[1];
        var rules = Rules.of(Batch.class).each("names", Batch::names, notBlank())
                .check("after", batch -> { calls[0]++; return false; }, "never_reached");
        var violations = rules.validate(new Batch(Collections.nCopies(10_000, "")));
        assertThat(violations).hasSize(Validation.MAX_VIOLATIONS);
        assertThat(violations.get(99)).isEqualTo(new Violation("names[99]", "not_blank"));
        assertThat(calls[0]).isZero();
    }

    @Test
    void neverEchoesTheInvalidInput() {
        var poison = "<script>POISON${1+1}#{7*7}%s</script>";
        var order = new Order(poison + "x".repeat(30), poison, 7, new Address(poison, poison),
                List.of(new Item(poison, -1)), List.of(poison), 2, 1);
        var violations = ORDER.validate(order);
        assertThat(violations).isNotEmpty();
        assertThat(violations.toString()).doesNotContain("POISON", "script", "${", "#{", "49", "%s");
        for (var violation : violations) {
            assertThat(violation.field()).matches("[a-z\\[\\]0-9._]+");
            assertThat(violation.code()).matches("[a-z_]+");
        }
    }

    @Test
    void sharedValidatorsGiveTheSameResultsAcrossThreads() throws Exception {
        var inputs = IntStream.range(0, 64).mapToObj(i -> new Order(i % 3 == 0 ? "" : "c" + i,
                i % 2 == 0 ? "user" + i + "@example.org" : "bad" + i, i % 7, valid().address(),
                List.of(new Item(i % 5 == 0 ? "bad" : "ABC-1234", i % 4)), List.of("t" + i), i % 3, 1)).toList();
        var expected = inputs.stream().map(ORDER::validate).toList();
        var threads = 8;
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int round = 0; round < 200; round++) {
                        for (int i = 0; i < inputs.size(); i++) {
                            if (!ORDER.validate(inputs.get(i)).equals(expected.get(i))) {
                                return false;
                            }
                        }
                    }
                    return true;
                }));
            }
            start.countDown();
            for (var future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
