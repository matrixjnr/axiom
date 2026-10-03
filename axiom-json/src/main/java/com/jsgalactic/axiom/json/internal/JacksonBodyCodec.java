package com.jsgalactic.axiom.json.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.ArrayType;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jsgalactic.axiom.codec.spi.BodyCodec;
import com.jsgalactic.axiom.error.DecodeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict JSON codec for {@code application/json}, discovered through {@link java.util.ServiceLoader}.
 * <p>
 * Decoding reads the content as strict UTF-8 (malformed bytes fail with {@code invalid_encoding};
 * no UTF-16/32 auto-detection), rejects unknown properties, duplicate keys, trailing content,
 * scalar coercion such as {@code "1"} for a number, floats for integers and null or missing
 * primitives, numbers or booleans for strings, and bounds nesting depth, string, name, number
 * and document length. Records, {@code Optional} and java.time types (as ISO-8601 strings only)
 * are supported; default typing is never enabled. Failures become {@link DecodeException} with a fixed code and, when the property
 * path consists of declared properties, that path; Jackson messages and input never leave this
 * class. Not application API.
 */
public final class JacksonBodyCodec implements BodyCodec {
    /** Deepest accepted object/array nesting. */
    static final int MAX_NESTING_DEPTH = 64;
    /** Longest accepted string value, in characters. */
    static final int MAX_STRING_LENGTH = 1024 * 1024;
    /** Longest accepted property name, in characters. */
    static final int MAX_NAME_LENGTH = 1024;
    /** Longest accepted number literal, in characters. */
    static final int MAX_NUMBER_LENGTH = 256;
    /** Longest accepted document, in characters; a fixed backstop independent of request limits. */
    static final long MAX_DOCUMENT_LENGTH = 64L * 1024 * 1024;

    private final ObjectMapper mapper;
    private final JsonFactory scanFactory;

    /** Creates the codec; called by the service loader. */
    public JacksonBodyCodec() {
        var constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNameLength(MAX_NAME_LENGTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .maxDocumentLength(MAX_DOCUMENT_LENGTH)
                .build();
        var factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        // Same limits; duplicates are tracked by scan() itself so they are told apart by structure.
        scanFactory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .disable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                // Untyped numbers keep their exact value instead of overflowing a double.
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                // java.time values are ISO-8601 strings both ways, and offsets are kept as sent.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                // Rejects lenient forms such as an empty string read as a null date.
                .defaultLeniency(false)
                .addModule(new Jdk8Module())
                .addModule(new JavaTimeModule())
                .addModule(finiteFloatingPoint())
                .addModule(strictValues())
                // A number or boolean is not read as text, mirroring the rejection of "1" for a number.
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
    }

    /**
     * Accepts java.time values and byte arrays only as JSON strings (ISO-8601 text and base64),
     * not as the numbers or arrays Jackson would otherwise read as timestamps or byte lists, and
     * UUIDs only in their canonical 36-character form.
     */
    private static SimpleModule strictValues() {
        var module = new SimpleModule("axiom-strict-values");
        module.addDeserializer(UUID.class, new CanonicalUuid());
        module.setDeserializerModifier(new BeanDeserializerModifier() {
            @Override public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config,
                    BeanDescription description, JsonDeserializer<?> deserializer) {
                return description.getBeanClass().getPackageName().equals("java.time")
                        ? new StringOnly(deserializer) : deserializer;
            }

            @Override public JsonDeserializer<?> modifyArrayDeserializer(DeserializationConfig config,
                    ArrayType type, BeanDescription description, JsonDeserializer<?> deserializer) {
                return type.getRawClass() == byte[].class ? new StringOnly(deserializer) : deserializer;
            }
        });
        return module;
    }

    /** Requires a JSON string before handing the value to the wrapped deserializer. */
    private static final class StringOnly extends DelegatingDeserializer {
        private static final long serialVersionUID = 1L;
        StringOnly(JsonDeserializer<?> delegate) { super(delegate); }

        @Override protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
            return new StringOnly(delegate);
        }

        @Override public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) { return context.handleUnexpectedToken(handledType(), parser); }
            return super.deserialize(parser, context);
        }
    }

    private static final class CanonicalUuid extends StdScalarDeserializer<UUID> {
        private static final long serialVersionUID = 1L;
        private static final Pattern CANONICAL =
                Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");
        CanonicalUuid() { super(UUID.class); }

        @Override public UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (UUID) context.handleUnexpectedToken(UUID.class, parser);
            }
            var text = parser.getText();
            if (!CANONICAL.matcher(text).matches()) {
                return (UUID) context.handleWeirdStringValue(UUID.class, text, "not a canonical UUID");
            }
            return UUID.fromString(text);
        }
    }

    /**
     * Rejects numbers that only fit a double or float as an infinity (for example {@code 1e400});
     * the failure is a mismatch on that property, reported as {@code type_mismatch}.
     */
    private static SimpleModule finiteFloatingPoint() {
        var module = new SimpleModule("axiom-finite-floating-point");
        module.addDeserializer(Double.class, new FiniteDouble(Double.class, null));
        module.addDeserializer(Double.TYPE, new FiniteDouble(Double.TYPE, 0.0));
        module.addDeserializer(Float.class, new FiniteFloat(Float.class, null));
        module.addDeserializer(Float.TYPE, new FiniteFloat(Float.TYPE, 0.0f));
        return module;
    }

    private static final class FiniteDouble extends NumberDeserializers.DoubleDeserializer {
        private static final long serialVersionUID = 1L;
        FiniteDouble(Class<Double> type, Double empty) { super(type, empty); }
        @Override public Double deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            var value = super.deserialize(parser, context);
            if (value != null && !Double.isFinite(value)) {
                throw context.weirdNumberException(value, handledType(), "out of range");
            }
            return value;
        }
    }

    private static final class FiniteFloat extends NumberDeserializers.FloatDeserializer {
        private static final long serialVersionUID = 1L;
        FiniteFloat(Class<Float> type, Float empty) { super(type, empty); }
        @Override public Float deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            var value = super.deserialize(parser, context);
            if (value != null && !Float.isFinite(value)) {
                throw context.weirdNumberException(value, handledType(), "out of range");
            }
            return value;
        }
    }

    /** The configured mapper, for tests. */
    ObjectMapper mapper() { return mapper; }

    @Override public Set<String> mediaTypes() { return Set.of("application/json"); }

    @Override public <T> T decode(byte[] content, Class<T> type) {
        return decode(ByteBuffer.wrap(content), type);
    }

    /** Reads the view through a stream, so the content is never copied as a whole. */
    @Override public <T> T decode(ByteBuffer content, Class<T> type) {
        try (var parser = mapper.createParser(reader(content.duplicate()))) {
            if (parser.nextToken() == null) { throw new DecodeException("empty_body"); }
            T value = mapper.readValue(parser, type);
            // The mapper does not check for trailing tokens; reading one here gives it its own code.
            if (parser.nextToken() != null) { throw new DecodeException("trailing_content"); }
            return value;
        } catch (InvalidDefinitionException unsupported) {
            // The target type cannot be deserialized: an application defect, not a client error.
            throw new IllegalStateException("JSON codec cannot decode type " + type.getName(), unsupported);
        } catch (IOException failure) {
            throw classify(failure, content);
        }
    }

    /**
     * Reads the content as strict UTF-8. RFC 8259 lets parsers ignore a UTF-8 byte order mark;
     * other encodings are not detected.
     */
    private static Reader reader(ByteBuffer content) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        int at = content.position();
        if (content.remaining() >= 3 && (content.get(at) & 0xff) == 0xef && (content.get(at + 1) & 0xff) == 0xbb
                && (content.get(at + 2) & 0xff) == 0xbf) {
            content.position(at + 3);
        }
        return new InputStreamReader(new BufferInputStream(content), decoder);
    }

    /** Streams a buffer's remaining bytes, advancing its position. */
    private static final class BufferInputStream extends InputStream {
        private final ByteBuffer buffer;

        BufferInputStream(ByteBuffer buffer) { this.buffer = buffer; }

        @Override public int read() { return buffer.hasRemaining() ? buffer.get() & 0xff : -1; }

        @Override public int read(byte[] target, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) { return 0; }
            if (!buffer.hasRemaining()) { return -1; }
            int count = Math.min(length, buffer.remaining());
            buffer.get(target, offset, count);
            return count;
        }

        @Override public int available() { return buffer.remaining(); }
    }

    /**
     * Maps a failure to a safe code by exception type and structure, never by message text.
     * Token-level failures (syntax, encoding, duplicate keys) can surface directly or wrapped by
     * databind with a misleading type; for those the content is scanned again, which reports the
     * first token-level problem precisely. Failures that the scan does not reproduce, such as an
     * integer too large for its field, are value mismatches.
     */
    private DecodeException classify(IOException failure, ByteBuffer content) {
        if (causedBy(failure, StreamConstraintsException.class)) { return new DecodeException("limit_exceeded"); }
        if (causedBy(failure, StreamReadException.class) || causedBy(failure, CharacterCodingException.class)) {
            var tokenFailure = scan(content);
            if (tokenFailure != null) { return tokenFailure; }
        }
        if (failure instanceof UnrecognizedPropertyException unknown) {
            var path = unknown.getPath();
            return decodeFailure("unknown_field", path.subList(0, Math.max(0, path.size() - 1)));
        }
        if (failure instanceof ValueInstantiationException invalid) {
            return decodeFailure("invalid_value", invalid.getPath());
        }
        if (failure instanceof MismatchedInputException mismatch) {
            return decodeFailure("type_mismatch", mismatch.getPath());
        }
        if (failure instanceof JsonMappingException mapping) {
            // A token-level cause the scan did not reproduce is a value that does not fit its type.
            return decodeFailure(causedBy(mapping, StreamReadException.class) ? "type_mismatch" : "invalid_value",
                    mapping.getPath());
        }
        if (failure instanceof StreamReadException) { return new DecodeException("type_mismatch"); }
        return new DecodeException("malformed_json");
    }

    /**
     * Reads every token of the content, with duplicate keys detected here rather than by the
     * parser, and returns the first token-level failure, or null when the token stream is valid.
     */
    private DecodeException scan(ByteBuffer content) {
        var names = new ArrayDeque<Set<String>>();
        try (var parser = scanFactory.createParser(reader(content.duplicate()))) {
            for (var token = parser.nextToken(); token != null; token = parser.nextToken()) {
                switch (token) {
                    case START_OBJECT -> names.push(new HashSet<>());
                    case START_ARRAY -> names.push(Set.of());
                    case END_OBJECT, END_ARRAY -> names.pop();
                    case FIELD_NAME -> {
                        if (!names.peek().add(parser.currentName())) { return new DecodeException("duplicate_field"); }
                    }
                    default -> { }
                }
            }
            return null;
        } catch (CharacterCodingException malformed) {
            return new DecodeException("invalid_encoding");
        } catch (StreamConstraintsException limit) {
            return new DecodeException("limit_exceeded");
        } catch (IOException malformed) {
            return new DecodeException("malformed_json");
        }
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) { return true; }
        }
        return false;
    }

    /**
     * Builds a failure whose field is a path of declared properties and indexes. The path stops
     * at the first map, because map keys are client input, and is dropped if it is not a safe
     * property path (for example a renamed property with unusual characters).
     */
    private static DecodeException decodeFailure(String code, List<JsonMappingException.Reference> path) {
        var field = new StringBuilder();
        for (var reference : path) {
            if (reference.getFrom() instanceof Map<?, ?>) { break; }
            if (reference.getFieldName() != null) {
                if (!field.isEmpty()) { field.append('.'); }
                field.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0 && !field.isEmpty()) {
                field.append('[').append(reference.getIndex()).append(']');
            } else {
                break;
            }
        }
        try {
            return new DecodeException(code, field.isEmpty() ? null : field.toString());
        } catch (IllegalArgumentException unsafe) {
            return new DecodeException(code);
        }
    }

    @Override public byte[] encode(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Response value of type " + value.getClass().getName()
                    + " cannot be encoded as JSON", failure);
        }
    }
}
