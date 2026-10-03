package com.jsgalactic.axiom.json.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.codec.spi.BodyCodec;
import com.jsgalactic.axiom.error.DecodeException;
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

    record Holder(Object any, Map<String, Integer> counts, List<Item> items, Item item) { }

    /**
     * Duplicate keys and trailing content keep their own codes wherever they occur; a fallback to
     * {@code malformed_json}, {@code type_mismatch} or {@code invalid_value} fails here.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "root record             | {\"any\":1,\"any\":2}                                       | duplicate_field",
            "untyped value           | {\"any\":{\"a\":1,\"a\":2}}                               | duplicate_field",
            "map                     | {\"counts\":{\"POISON\":1,\"POISON\":2}}                  | duplicate_field",
            "record in list          | {\"items\":[{\"name\":\"a\",\"name\":\"b\",\"quantity\":1}]} | duplicate_field",
            "nested record           | {\"item\":{\"quantity\":1,\"quantity\":2,\"name\":\"a\"}} | duplicate_field",
            "after other properties  | {\"counts\":{},\"items\":[],\"counts\":{}}                | duplicate_field",
            "second root object      | {\"any\":1} {\"any\":2}                                     | trailing_content",
            "second root array       | {\"any\":1}[]                                                 | trailing_content",
            "second root scalar      | {\"any\":1} 7                                                 | trailing_content",
            "second root string      | {\"any\":1}\"POISON\"                                       | trailing_content",
    })
    void keepsSpecificCodesForDuplicatesAndTrailingContent(String name, String json, String code) {
        assertDecodeFailure(utf8(json), Holder.class, code, null);
    }

    @Test void reportsTrailingContentAfterScalarAndArrayRoots() {
        assertDecodeFailure(utf8("1 2"), Integer.class, "trailing_content", null);
        assertDecodeFailure(utf8("\"a\" \"b\""), String.class, "trailing_content", null);
        assertDecodeFailure(utf8("[1] [2]"), List.class, "trailing_content", null);
        assertDecodeFailure(utf8("{\"a\":{\"b\":1,\"b\":1}}"), Map.class, "duplicate_field", null);
        assertDecodeFailure(utf8("[{\"b\":1,\"c\":[],\"b\":1}]"), Object.class, "duplicate_field", null);
    }

    @Test void reportsNestedSyntaxErrorsAsMalformed() {
        assertDecodeFailure(utf8("{\"item\":{\"name\":POISON}}"), Holder.class, "malformed_json", null);
        assertDecodeFailure(utf8("{\"items\":[{\"name\":\"a\",]}"), Holder.class, "malformed_json", null);
        assertDecodeFailure(utf8("{\"item\":{\"name\":\"a\",\"quantity\":1}"), Holder.class, "malformed_json", null);
    }

    @Test void reportsIntegerOverflowAsTypeMismatch() {
        assertDecodeFailure(utf8("{\"name\":\"a\",\"quantity\":99999999999}"), Item.class, "type_mismatch", "quantity");
        assertDecodeFailure(utf8("{\"item\":{\"name\":\"a\",\"quantity\":99999999999}}"), Holder.class,
                "type_mismatch", "item.quantity");
    }

    /** The first problem in document order is reported, whichever kind of problem it is. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "overflow, then duplicate key   | {\"item\":{\"name\":\"a\",\"quantity\":99999999999},\"any\":1,\"any\":2} | type_mismatch   | item.quantity",
            "duplicate key, then overflow   | {\"any\":1,\"any\":2,\"item\":{\"name\":\"a\",\"quantity\":99999999999}} | duplicate_field |",
            "overflow, then syntax error    | {\"item\":{\"name\":\"a\",\"quantity\":99999999999},\"any\":POISON}          | type_mismatch   | item.quantity",
            "syntax error, then overflow    | {\"any\":POISON,\"item\":{\"name\":\"a\",\"quantity\":99999999999}}          | malformed_json  |",
            "overflow, then excess nesting  | {\"item\":{\"name\":\"a\",\"quantity\":99999999999},\"any\":%s}              | type_mismatch   | item.quantity",
            "overflow, then trailing        | {\"item\":{\"name\":\"a\",\"quantity\":99999999999}} 7                            | type_mismatch   | item.quantity",
            "overflow, then bad UTF-8       | {\"item\":{\"name\":\"a\",\"quantity\":99999999999},\"any\":\"\u00e9\"}      | type_mismatch   | item.quantity",
    })
    void reportsTheFirstProblemInDocumentOrder(String name, String json, String code, String field) {
        var text = json.replace("%s", "[".repeat(JacksonBodyCodec.MAX_NESTING_DEPTH + 1));
        var bytes = text.contains("\u00e9") ? corrupt(text) : utf8(text);
        assertDecodeFailure(bytes, Holder.class, code, field);
    }

    /** Replaces the {@code é} marker with a lone continuation byte, which is not valid UTF-8. */
    private static byte[] corrupt(String text) {
        var head = utf8(text.substring(0, text.indexOf('\u00e9')));
        var tail = utf8(text.substring(text.indexOf('\u00e9') + 1));
        var bytes = new byte[head.length + 1 + tail.length];
        System.arraycopy(head, 0, bytes, 0, head.length);
        bytes[head.length] = (byte) 0x80;
        System.arraycopy(tail, 0, bytes, head.length + 1, tail.length);
        return bytes;
    }

    @Test void reportsInvalidUtf8AtItsPositionInTheDocument() {
        // Invalid bytes after a syntax error do not mask it, and before one they are reported first.
        var invalid = new byte[] {(byte) 0x80};
        assertDecodeFailure(concat(utf8("{\"any\":POISON,\"x\":\""), invalid, utf8("\"}")), Holder.class, "malformed_json", null);
        assertDecodeFailure(concat(utf8("{\"any\":\""), invalid, utf8("\",\"x\":POISON}")), Holder.class, "invalid_encoding", null);
        assertDecodeFailure(concat(utf8("{\"any\":1,\"any\":2,\"x\":\""), invalid, utf8("\"}")), Holder.class, "duplicate_field", null);
        assertDecodeFailure(concat(utf8("{\"name\":\"caf"), new byte[] {(byte) 0xc3}), Item.class, "invalid_encoding", null);
        // The parser looks one token ahead after a name; a later invalid byte must not hide an earlier mismatch.
        assertDecodeFailure(concat(utf8("{\"item\":{\"name\":\"a\",\"quantity\":99999999999},\"any\":"), invalid, utf8("}")),
                Holder.class, "type_mismatch", "item.quantity");
    }

    private static byte[] concat(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (var part : parts) { out.writeBytes(part); }
        return out.toByteArray();
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

    @Test void decodesFromAReadOnlyViewWithoutCopyingIt() {
        var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        boolean measurable = threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled();
        if (Boolean.getBoolean("axiom.requireAllocationTests")) {
            assertThat(measurable).as("thread allocation measurement is required by axiom.requireAllocationTests").isTrue();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(measurable);
        int padding = 8 * 1024 * 1024;
        var content = new byte[padding + 64];
        java.util.Arrays.fill(content, (byte) ' ');
        var json = utf8("xx{\"name\":\"view\",\"quantity\":3}");
        System.arraycopy(json, 0, content, 0, json.length);
        var view = java.nio.ByteBuffer.wrap(content).position(2).asReadOnlyBuffer();
        codec.decode(view.duplicate(), Item.class); // Loads and links the decoding path before measuring.

        long before = threads.getCurrentThreadAllocatedBytes();
        var item = codec.decode(view, Item.class);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertThat(item).isEqualTo(new Item("view", 3));
        assertThat(allocated).as("bytes allocated while decoding a %d-byte body", content.length).isLessThan(padding / 8);
        assertDecodeFailure(utf8("xx{\"name\":\"a\",\"name\":\"b\",\"quantity\":1}"), Item.class, "malformed_json", null);
        assertThatThrownBy(() -> codec.decode(java.nio.ByteBuffer.wrap(utf8("xx{\"name\":\"a\",\"name\":\"b\",\"quantity\":1}"))
                .position(2).asReadOnlyBuffer(), Item.class))
                .isInstanceOfSatisfying(DecodeException.class, failure -> assertThat(failure.code()).isEqualTo("duplicate_field"));
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
