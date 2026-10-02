package io.axiom.json.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.axiom.codec.spi.BodyCodec;
import io.axiom.error.DecodeException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JacksonBodyCodecTest {
    record Item(String name, int quantity) { }
    record Order(String id, List<Item> items, Map<String, Item> extras) { }
    record Checked(String name) {
        Checked { if (name == null || name.isBlank()) { throw new IllegalArgumentException("POISON from constructor"); } }
    }

    private final JacksonBodyCodec codec = new JacksonBodyCodec();

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test void isDiscoveredForApplicationJson() {
        var codecs = ServiceLoader.load(BodyCodec.class).stream().map(ServiceLoader.Provider::get).toList();
        assertThat(codecs).hasSize(1).first().isInstanceOf(JacksonBodyCodec.class);
        assertThat(codec.mediaTypes()).containsExactly("application/json");
        assertThat(codec.supports("application/json")).isTrue();
    }

    @Test void roundTripsRecords() {
        var order = new Order("o-1", List.of(new Item("pen", 2)), Map.of("gift", new Item("card", 1)));
        var json = codec.encode(order);
        assertThat(new String(json, StandardCharsets.UTF_8)).startsWith("{\"id\":\"o-1\",\"items\":[{\"name\":\"pen\"");
        assertThat(codec.decode(json, Order.class)).isEqualTo(order);
        assertThat(codec.decode(utf8("{\"name\":\"café €\",\"quantity\":1}"), Item.class).name()).isEqualTo("café €");
        assertThat(codec.decode(utf8("﻿{\"name\":\"bom\",\"quantity\":1}"), Item.class).name()).isEqualTo("bom");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "unknown field             | {\"name\":\"a\",\"quantity\":1,\"POISON\":true}          | unknown_field   |",
            "nested unknown field      | {\"id\":\"o\",\"items\":[{\"name\":\"a\",\"quantity\":1,\"POISON\":1}],\"extras\":{}} | unknown_field | items[0]",
            "trailing tokens           | {\"name\":\"a\",\"quantity\":1} {\"POISON\":1}           | trailing_content |",
            "trailing garbage          | {\"name\":\"a\",\"quantity\":1}POISON                     | malformed_json  |",
            "wrong type                | {\"name\":\"a\",\"quantity\":\"POISON\"}                  | type_mismatch   | quantity",
            "string coerced to number  | {\"name\":\"a\",\"quantity\":\"12\"}                      | type_mismatch   | quantity",
            "float for int             | {\"name\":\"a\",\"quantity\":1.5}                         | type_mismatch   | quantity",
            "null primitive            | {\"name\":\"a\",\"quantity\":null}                        | type_mismatch   | quantity",
            "missing primitive         | {\"name\":\"a\"}                                          | type_mismatch   | quantity",
            "duplicate key             | {\"name\":\"a\",\"name\":\"POISON\",\"quantity\":1}      | duplicate_field |",
            "malformed                 | {\"name\":POISON}                                         | malformed_json  |",
            "single quotes             | {'name':'a','quantity':1}                                 | malformed_json  |",
            "comments                  | {/*POISON*/\"name\":\"a\",\"quantity\":1}                 | malformed_json  |",
            "only whitespace           | '   '                                                     | empty_body      |",
            "array for object          | [1,2]                                                     | type_mismatch   |",
    })
    void rejectsWithSafeCodes(String name, String json, String code, String field) {
        assertDecodeFailure(utf8(json), json.contains("\"items\"") ? Order.class : Item.class,
                code, field);
    }

    @Test void mapKeysAreNeverReportedAsFields() {
        var json = "{\"id\":\"o\",\"items\":[],\"extras\":{\"POISON<key>\":{\"name\":\"x\",\"quantity\":\"bad\"}}}";
        assertDecodeFailure(utf8(json), Order.class, "type_mismatch", "extras");
    }

    @Test void reportsConstructorValidationWithoutItsMessage() {
        assertDecodeFailure(utf8("{\"name\":\" \"}"), Checked.class, "invalid_value", null);
    }

    @Test void boundsNestingDepth() {
        var bomb = "[".repeat(10_000) + "]".repeat(10_000);
        assertDecodeFailure(utf8(bomb), Object.class, "limit_exceeded", null);
        var nested = "{\"a\":".repeat(JacksonBodyCodec.MAX_NESTING_DEPTH + 1) + "1" + "}".repeat(JacksonBodyCodec.MAX_NESTING_DEPTH + 1);
        assertDecodeFailure(utf8(nested), Object.class, "limit_exceeded", null);
    }

    @Test void boundsStringNameAndNumberLengths() {
        var longString = "{\"name\":\"" + "x".repeat(JacksonBodyCodec.MAX_STRING_LENGTH + 1) + "\",\"quantity\":1}";
        assertDecodeFailure(utf8(longString), Item.class, "limit_exceeded", null);
        var longName = "{\"" + "n".repeat(JacksonBodyCodec.MAX_NAME_LENGTH + 1) + "\":1}";
        assertDecodeFailure(utf8(longName), Object.class, "limit_exceeded", null);
        var longNumber = "[" + "9".repeat(JacksonBodyCodec.MAX_NUMBER_LENGTH + 1) + "]";
        assertDecodeFailure(utf8(longNumber), Object.class, "limit_exceeded", null);
    }

    @Test void acceptsOnlyUtf8() {
        var latin1 = "{\"name\":\"café\",\"quantity\":1}".getBytes(StandardCharsets.ISO_8859_1);
        assertDecodeFailure(latin1, Item.class, "invalid_encoding", null);
        var utf16 = "{\"name\":\"a\",\"quantity\":1}".getBytes(StandardCharsets.UTF_16LE);
        assertDecodeFailure(utf16, Item.class, "malformed_json", null);
        var utf16WithBom = "{\"name\":\"a\",\"quantity\":1}".getBytes(StandardCharsets.UTF_16);
        assertDecodeFailure(utf16WithBom, Item.class, "invalid_encoding", null);
    }

    @Test void reportsUnencodableAndUndecodableTypesAsServerErrors() {
        assertThatIllegalStateException().isThrownBy(() -> codec.encode(new Object()));
        assertThatIllegalStateException().isThrownBy(() -> codec.decode(utf8("{}"), Runnable.class));
    }

    private void assertDecodeFailure(byte[] json, Class<?> type, String code, String field) {
        assertThatThrownBy(() -> codec.decode(json, type)).isInstanceOfSatisfying(DecodeException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.field().orElse(null)).isEqualTo(field);
            assertThat(failure.getMessage()).isEqualTo("400 " + code);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.violations().toString()).doesNotContain("POISON", "jackson", "Exception");
        });
    }

    record Measurement(double value, Double boxed, float ratio, Float boxedRatio) { }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "{\"value\":1e400,\"boxed\":1,\"ratio\":1,\"boxedRatio\":1}   | value",
            "{\"value\":-1e400,\"boxed\":1,\"ratio\":1,\"boxedRatio\":1}  | value",
            "{\"value\":1,\"boxed\":1e400,\"ratio\":1,\"boxedRatio\":1}   | boxed",
            "{\"value\":1,\"boxed\":1,\"ratio\":1e39,\"boxedRatio\":1}    | ratio",
            "{\"value\":1,\"boxed\":1,\"ratio\":1,\"boxedRatio\":-1e39}   | boxedRatio"
    })
    void rejectsNumbersThatOverflowToInfinity(String json, String field) {
        assertDecodeFailure(utf8(json), Measurement.class, "type_mismatch", field);
    }

    @Test void keepsFiniteFloatingPointAndExactUntypedNumbers() {
        var measurement = codec.decode(utf8("{\"value\":1.5e300,\"boxed\":null,\"ratio\":0.25,\"boxedRatio\":3}"),
                Measurement.class);
        assertThat(measurement).isEqualTo(new Measurement(1.5e300, null, 0.25f, 3f));
        assertThat(codec.decode(utf8("[1e400]"), List.class).getFirst())
                .isEqualTo(new java.math.BigDecimal("1e400"));
        assertDecodeFailure(utf8("{\"value\":NaN}"), Measurement.class, "malformed_json", null);
    }
}
