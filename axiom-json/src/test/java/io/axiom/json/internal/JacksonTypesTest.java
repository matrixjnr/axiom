package io.axiom.json.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.axiom.error.DecodeException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Supported value types beyond strings, numbers and records, and their strict input forms. */
class JacksonTypesTest {
    record Event(Instant at, LocalDate day, OffsetDateTime when, Duration length) { }
    enum Speed { SLOW, FAST }
    record Shipping(Speed speed) { }
    record Identified(UUID id) { }
    record Money(BigDecimal amount) { }
    record Blob(byte[] data) { }
    record Profile(String name, Optional<String> nickname) { }

    private final JacksonBodyCodec codec = new JacksonBodyCodec();

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private String encode(Object value) { return new String(codec.encode(value), StandardCharsets.UTF_8); }

    @Test void roundTripsJavaTimeAsIso8601Strings() {
        var event = new Event(Instant.parse("2024-02-29T10:15:30.123456789Z"), LocalDate.of(2024, 2, 29),
                OffsetDateTime.of(2024, 2, 29, 10, 15, 30, 0, ZoneOffset.ofHours(2)), Duration.ofMinutes(90));
        var json = encode(event);
        assertThat(json).isEqualTo("{\"at\":\"2024-02-29T10:15:30.123456789Z\",\"day\":\"2024-02-29\","
                + "\"when\":\"2024-02-29T10:15:30+02:00\",\"length\":\"PT1H30M\"}");
        // The offset is kept as sent rather than adjusted to UTC.
        assertThat(codec.decode(utf8(json), Event.class)).isEqualTo(event);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "impossible date         | {\"day\":\"2024-02-30\"}                  | day",
            "not a date              | {\"day\":\"POISON\"}                      | day",
            "empty string            | {\"day\":\"\"}                            | day",
            "date as array           | {\"day\":[2024,2,29]}                     | day",
            "date as epoch day       | {\"day\":19782}                           | day",
            "instant as epoch second | {\"at\":1700000000}                        | at",
            "instant as decimal      | {\"at\":1700000000.5}                     | at",
            "instant without zone    | {\"at\":\"2024-02-29T10:15:30\"}          | at",
            "date-time as object     | {\"when\":{\"POISON\":1}}                  | when",
            "duration as number      | {\"length\":60}                           | length",
            "malformed duration      | {\"length\":\"PT-POISON\"}                | length",
    })
    void rejectsNonStringOrMalformedJavaTime(String name, String json, String field) {
        assertDecodeFailure(json, Event.class, "type_mismatch", field);
    }

    @Test void readsEnumsByExactName() {
        assertThat(codec.decode(utf8("{\"speed\":\"FAST\"}"), Shipping.class)).isEqualTo(new Shipping(Speed.FAST));
        assertThat(encode(new Shipping(Speed.SLOW))).isEqualTo("{\"speed\":\"SLOW\"}");
        assertDecodeFailure("{\"speed\":\"POISON\"}", Shipping.class, "type_mismatch", "speed");
        assertDecodeFailure("{\"speed\":\"fast\"}", Shipping.class, "type_mismatch", "speed");
        assertDecodeFailure("{\"speed\":1}", Shipping.class, "type_mismatch", "speed");
        assertDecodeFailure("{\"speed\":\"1\"}", Shipping.class, "type_mismatch", "speed");
    }

    @Test void readsUuidsOnlyInCanonicalForm() {
        var id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertThat(encode(new Identified(id))).isEqualTo("{\"id\":\"123e4567-e89b-12d3-a456-426614174000\"}");
        assertThat(codec.decode(utf8("{\"id\":\"123E4567-E89B-12D3-A456-426614174000\"}"), Identified.class).id())
                .isEqualTo(id);
        assertDecodeFailure("{\"id\":\"Ej5FZ+ibEtOkVkJmFBdAAA==\"}", Identified.class, "type_mismatch", "id");
        assertDecodeFailure("{\"id\":\"1-1-1-1-1\"}", Identified.class, "type_mismatch", "id");
        assertDecodeFailure("{\"id\":\"POISON\"}", Identified.class, "type_mismatch", "id");
        assertDecodeFailure("{\"id\":42}", Identified.class, "type_mismatch", "id");
    }

    @Test void keepsBigDecimalsExactAndRejectsStrings() {
        var exact = codec.decode(utf8("{\"amount\":12.345678901234567890123}"), Money.class);
        assertThat(exact.amount()).isEqualTo(new BigDecimal("12.345678901234567890123"));
        assertThat(encode(exact)).isEqualTo("{\"amount\":12.345678901234567890123}");
        assertDecodeFailure("{\"amount\":\"12.3\"}", Money.class, "type_mismatch", "amount");
    }

    @Test void readsByteArraysOnlyAsBase64Strings() {
        assertThat(encode(new Blob(new byte[] {1, 2, 3}))).isEqualTo("{\"data\":\"AQID\"}");
        assertThat(codec.decode(utf8("{\"data\":\"AQID\"}"), Blob.class).data()).containsExactly(1, 2, 3);
        assertDecodeFailure("{\"data\":[1,2,3]}", Blob.class, "type_mismatch", "data");
        assertDecodeFailure("{\"data\":\"@POISON@\"}", Blob.class, "type_mismatch", "data");
    }

    @Test void mapsOptionalToPresentOrEmpty() {
        assertThat(codec.decode(utf8("{\"name\":\"a\",\"nickname\":\"b\"}"), Profile.class))
                .isEqualTo(new Profile("a", Optional.of("b")));
        assertThat(codec.decode(utf8("{\"name\":\"a\",\"nickname\":null}"), Profile.class))
                .isEqualTo(new Profile("a", Optional.empty()));
        assertThat(codec.decode(utf8("{\"name\":\"a\"}"), Profile.class))
                .isEqualTo(new Profile("a", Optional.empty()));
        assertThat(encode(new Profile("a", Optional.empty()))).isEqualTo("{\"name\":\"a\",\"nickname\":null}");
        assertThat(encode(new Profile("a", Optional.of("b")))).isEqualTo("{\"name\":\"a\",\"nickname\":\"b\"}");
        assertDecodeFailure("{\"name\":\"a\",\"nickname\":7}", Profile.class, "type_mismatch", "nickname");
    }

    record Named(String name) { }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "integer  | {\"name\":7}",
            "decimal  | {\"name\":7.5}",
            "boolean  | {\"name\":true}",
            "object   | {\"name\":{\"POISON\":1}}",
            "array    | {\"name\":[\"POISON\"]}",
    })
    void rejectsScalarsCoercedToStrings(String name, String json) {
        assertDecodeFailure(json, Named.class, "type_mismatch", "name");
    }

    @Test void neverResolvesTypesFromInput() {
        var mapper = codec.mapper();
        assertThat(mapper.getDeserializationConfig().getDefaultTyper(mapper.constructType(Object.class))).isNull();
        assertThat(mapper.getSerializationConfig().getDefaultTyper(mapper.constructType(Object.class))).isNull();
        assertThat(codec.decode(utf8("[\"java.net.URL\",\"http://example.com\"]"), Object.class))
                .isEqualTo(List.of("java.net.URL", "http://example.com"));
        assertThat(codec.decode(utf8("{\"@class\":\"java.net.URL\"}"), Object.class))
                .isEqualTo(Map.of("@class", "java.net.URL"));
    }

    private void assertDecodeFailure(String json, Class<?> type, String code, String field) {
        assertThatThrownBy(() -> codec.decode(utf8(json), type)).isInstanceOfSatisfying(DecodeException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.field().orElse(null)).isEqualTo(field);
            assertThat(failure.getMessage()).isEqualTo("400 " + code);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.violations().toString()).doesNotContain("POISON", "jackson", "Exception", "java.time");
        });
    }
}
