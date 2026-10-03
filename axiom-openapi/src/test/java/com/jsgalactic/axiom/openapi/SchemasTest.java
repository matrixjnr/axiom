package com.jsgalactic.axiom.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.validation.Rule;
import com.jsgalactic.axiom.validation.Rules;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SchemasTest {
    private static Schemas schemas() { return new Schemas(32, Map.of(), Map.of()); }

    @Test void scalarsMapToJsonSchemaTypesAndFormats() {
        var s = schemas();
        assertThat(s.schema(int.class, "t")).containsEntry("type", "integer").containsEntry("format", "int32");
        assertThat(s.schema(Long.class, "t")).containsEntry("format", "int64");
        assertThat(s.schema(double.class, "t")).containsEntry("type", "number").containsEntry("format", "double");
        assertThat(s.schema(BigDecimal.class, "t")).containsEntry("type", "number");
        assertThat(s.schema(boolean.class, "t")).containsEntry("type", "boolean");
        assertThat(s.schema(String.class, "t")).containsEntry("type", "string");
        assertThat(s.schema(UUID.class, "t")).containsEntry("format", "uuid");
        assertThat(s.schema(Instant.class, "t")).containsEntry("format", "date-time");
        assertThat(s.schema(LocalDate.class, "t")).containsEntry("format", "date");
        assertThat(s.schema(Duration.class, "t")).containsEntry("format", "duration");
        assertThat(s.schema(byte[].class, "t")).containsEntry("format", "byte");
        assertThat(s.schema(char.class, "t")).containsEntry("maxLength", 1);
    }

    @Test void collectionsArraysMapsAndOptionalsAreInlined() {
        var s = schemas();
        assertThat(s.schema(Fixtures.listOf(String.class), "t")).containsEntry("type", "array")
                .containsEntry("items", Map.of("type", "string"));
        assertThat(s.schema(Fixtures.parameterized(Set.class, int.class), "t")).containsEntry("uniqueItems", true);
        assertThat(s.schema(String[].class, "t")).containsEntry("type", "array");
        assertThat(s.schema(Fixtures.parameterized(Map.class, String.class, Long.class), "t"))
                .containsEntry("type", "object").containsKey("additionalProperties");
        assertThat(s.schema(Fixtures.parameterized(Optional.class, String.class), "t")).containsEntry("type", "string");
        assertThat(s.components()).isEmpty();
    }

    @Test void recordsBeansAndEnumsBecomeNamedSchemas() {
        var s = schemas();
        assertThat(s.schema(Fixtures.Priority.class, "t")).containsEntry("$ref", "#/components/schemas/Priority");
        assertThat(s.components().get("Priority")).containsEntry("enum", List.of("LOW", "HIGH"));
        s.schema(Fixtures.Note.class, "t");
        assertThat(s.components()).containsKeys("Note", "Priority");
        assertThat(s.components().get("Note").get("required")).isEqualTo(List.of("version"));
    }

    @Test void selfReferencesAndCyclesBecomeReferences() {
        var s = schemas();
        s.schema(Fixtures.Node.class, "t");
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) s.components().get("Node").get("properties");
        assertThat(properties.get("parent")).isEqualTo(Map.of("$ref", "#/components/schemas/Node"));
        assertThat(properties.get("children")).isEqualTo(
                Map.of("type", "array", "items", Map.of("$ref", "#/components/schemas/Node")));
    }

    record A(B b) { }
    record B(A a) { }

    @Test void mutualCyclesTerminate() {
        var s = schemas();
        s.schema(A.class, "t");
        assertThat(s.components()).containsOnlyKeys("A", "B");
    }

    record D0(D1 next) { }
    record D1(D2 next) { }
    record D2(D3 next) { }
    record D3(String leaf) { }

    @Test void theDepthBoundStopsDeepChainsWithAClearMessage() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Schemas(2, Map.of(), Map.of()).schema(D0.class, "GET /d"))
                .withMessageContaining("D2.next").withMessageContaining("deeper than 2");
        assertThat(new Schemas(4, Map.of(), Map.of()).schema(D0.class, "GET /d")).containsKey("$ref");
    }

    @Test void genericRecordsAreNamedByTheirArguments() {
        var s = schemas();
        s.schema(Fixtures.parameterized(Fixtures.Page.class, Fixtures.Note.class), "t");
        s.schema(Fixtures.parameterized(Fixtures.Page.class, String.class), "t");
        assertThat(s.components()).containsKeys("PageOfNote", "PageOfString", "Note");
        assertThatIllegalArgumentException().isThrownBy(() -> schemas().schema(Fixtures.Page.class, "GET /p"))
                .withMessageContaining("without type arguments");
    }

    @Test void unsupportedTypesFailWithTheirLocation() {
        assertThatIllegalArgumentException().isThrownBy(() -> schemas().schema(Object.class, "GET /o"))
                .withMessageContaining("GET /o").withMessageContaining("Object is not a supported type");
        assertThatIllegalArgumentException().isThrownBy(() -> schemas().schema(Runnable.class, "t"));
        assertThatIllegalArgumentException().isThrownBy(() -> schemas().schema(Thread.class, "t"));
        assertThatIllegalArgumentException().isThrownBy(() -> schemas().schema(Fixtures.parameterized(Map.class,
                int.class, String.class), "t")).withMessageContaining("map keys must be String");
        assertThatIllegalArgumentException().isThrownBy(() -> schemas().schema(List.class, "t"));
        assertThatIllegalArgumentException().isThrownBy(() -> schemas().simple(Fixtures.Note.class, "t"))
                .withMessageContaining("parameter");
    }

    @Test void parametersAreScalarsEnumsOrListsOfThose() {
        var s = schemas();
        assertThat(s.simple(Fixtures.Priority.class, "t")).containsEntry("enum", List.of("LOW", "HIGH"));
        assertThat(s.simple(Fixtures.listOf(UUID.class), "t")).containsEntry("type", "array");
        assertThat(s.components()).isEmpty();
    }

    @Test void twoClassesWithOneSimpleNameCollideUntilOneIsRenamed() {
        record Same(String a) { }
        var s = schemas();
        s.schema(Same.class, "t");
        class Holder {
            record Same(int b) { }
        }
        assertThatIllegalArgumentException().isThrownBy(() -> s.schema(Holder.Same.class, "t"))
                .withMessageContaining("Same").withMessageContaining("Builder.schemaName");
        var renamed = new Schemas(32, Map.of(Holder.Same.class, "Other"), Map.of());
        renamed.schema(Same.class, "t");
        renamed.schema(Holder.Same.class, "t");
        assertThat(renamed.components()).containsOnlyKeys("Same", "Other");
    }

    record Constrained(String name, String code, List<String> tags, int age, String mail, String kind, Fixtures.Priority level) { }

    @Test void validationRulesBecomeSchemaConstraints() {
        var rules = Rules.of(Constrained.class)
                .field("name", Constrained::name, Rule.notNull(), Rule.notBlank(), Rule.length(2, 20))
                .field("code", Constrained::code, Rule.pattern("[A-Z]{3}"))
                .field("tags", Constrained::tags, Rule.size(1, 5))
                .each("tags", Constrained::tags, Rule.maxLength(8))
                .field("age", Constrained::age, Rule.range(0, 150), Rule.min(18))
                .field("mail", Constrained::mail, Rule.email())
                .field("kind", Constrained::kind, Rule.oneOf("a", "b"))
                .field("level", Constrained::level, Rule.notNull());
        var s = new Schemas(32, Map.of(), Map.of(Constrained.class, rules.constraints()));
        s.schema(Constrained.class, "t");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Map<String, Object>>) s.components().get("Constrained").get("properties");

        assertThat(props.get("name")).containsEntry("minLength", 2L).containsEntry("maxLength", 20L);
        assertThat(props.get("code")).containsEntry("pattern", "[A-Z]{3}");
        assertThat(props.get("tags")).containsEntry("minItems", 1L).containsEntry("maxItems", 5L);
        assertThat(props.get("tags").get("items")).isEqualTo(Map.of("type", "string", "maxLength", 8L));
        assertThat(props.get("age")).containsEntry("minimum", 18L).containsEntry("maximum", 150L);
        assertThat(props.get("mail")).containsEntry("format", "email");
        assertThat(props.get("kind")).containsEntry("enum", List.of("a", "b"));
        assertThat(s.components().get("Constrained").get("required")).isEqualTo(List.of("name", "age", "level"));
    }

    /** Rules are typed, so a mismatch with the schema needs an unchecked cast. */
    @SuppressWarnings("unchecked")
    private static Rule<Object> unsafe(Rule<?> rule) { return (Rule<Object>) rule; }

    @Test void ruleMismatchesFailInsteadOfBeingIgnored() {
        var wrongType = Rules.of(Constrained.class).field("age", Constrained::age, unsafe(Rule.maxLength(3)));
        assertThatIllegalArgumentException().isThrownBy(() -> new Schemas(32, Map.of(),
                Map.of(Constrained.class, wrongType.constraints())).schema(Constrained.class, "t"))
                .withMessageContaining("LENGTH").withMessageContaining("Constrained.age");
        var unknown = Rules.of(Constrained.class).field("ghost", Constrained::name, Rule.notNull());
        assertThatIllegalArgumentException().isThrownBy(() -> new Schemas(32, Map.of(),
                Map.of(Constrained.class, unknown.constraints())).schema(Constrained.class, "t"))
                .withMessageContaining("'ghost'");
        var onReference = Rules.of(Constrained.class).field("level", Constrained::level, unsafe(Rule.notBlank()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Schemas(32, Map.of(),
                Map.of(Constrained.class, onReference.constraints())).schema(Constrained.class, "t"))
                .withMessageContaining("reference");
        var notAList = Rules.of(Constrained.class).each("name", Constrained::tags, Rule.maxLength(3));
        assertThatIllegalArgumentException().isThrownBy(() -> new Schemas(32, Map.of(),
                Map.of(Constrained.class, notAList.constraints())).schema(Constrained.class, "t"))
                .withMessageContaining("list or array");
    }
}
