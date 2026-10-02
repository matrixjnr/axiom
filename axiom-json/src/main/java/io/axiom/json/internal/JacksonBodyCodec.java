package io.axiom.json.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.axiom.codec.spi.BodyCodec;
import io.axiom.error.DecodeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict JSON codec for {@code application/json}, discovered through {@link java.util.ServiceLoader}.
 * <p>
 * Decoding reads the content as strict UTF-8 (malformed bytes fail with {@code invalid_encoding};
 * no UTF-16/32 auto-detection), rejects unknown properties, duplicate keys, trailing content,
 * scalar coercion such as {@code "1"} for a number, floats for integers and null or missing
 * primitives, and bounds nesting depth, string, name, number and document length. Records are
 * supported. Failures become {@link DecodeException} with a fixed code and, when the property
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
                .addModule(finiteFloatingPoint())
                .build();
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

    @Override public Set<String> mediaTypes() { return Set.of("application/json"); }

    @Override public <T> T decode(byte[] content, Class<T> type) {
        try (var parser = mapper.createParser(reader(content))) {
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
    private static Reader reader(byte[] content) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        int start = content.length >= 3 && (content[0] & 0xff) == 0xef && (content[1] & 0xff) == 0xbb
                && (content[2] & 0xff) == 0xbf ? 3 : 0;
        return new InputStreamReader(new ByteArrayInputStream(content, start, content.length - start), decoder);
    }

    /**
     * Maps a failure to a safe code by exception type and structure, never by message text.
     * Token-level failures (syntax, encoding, duplicate keys) can surface directly or wrapped by
     * databind with a misleading type; for those the content is scanned again, which reports the
     * first token-level problem precisely. Failures that the scan does not reproduce, such as an
     * integer too large for its field, are value mismatches.
     */
    private DecodeException classify(IOException failure, byte[] content) {
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
    private DecodeException scan(byte[] content) {
        var names = new ArrayDeque<Set<String>>();
        try (var parser = scanFactory.createParser(reader(content))) {
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
