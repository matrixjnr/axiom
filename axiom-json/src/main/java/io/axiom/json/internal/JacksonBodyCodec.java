package io.axiom.json.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.axiom.codec.spi.BodyCodec;
import io.axiom.error.DecodeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
        mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
    }

    @Override public Set<String> mediaTypes() { return Set.of("application/json"); }

    @Override public <T> T decode(byte[] content, Class<T> type) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // RFC 8259 lets parsers ignore a UTF-8 byte order mark; other encodings are not detected.
        int start = content.length >= 3 && (content[0] & 0xff) == 0xef && (content[1] & 0xff) == 0xbb
                && (content[2] & 0xff) == 0xbf ? 3 : 0;
        var input = new ByteArrayInputStream(content, start, content.length - start);
        try (var parser = mapper.createParser(new InputStreamReader(input, decoder))) {
            if (parser.nextToken() == null) { throw new DecodeException("empty_body"); }
            T value = mapper.readValue(parser, type);
            // Checked explicitly so trailing content gets its own code; the mapper also rejects it.
            if (parser.nextToken() != null) { throw new DecodeException("trailing_content"); }
            return value;
        } catch (CharacterCodingException malformed) {
            throw new DecodeException("invalid_encoding");
        } catch (StreamConstraintsException limit) {
            throw new DecodeException("limit_exceeded");
        } catch (InvalidDefinitionException unsupported) {
            // The target type cannot be deserialized: an application defect, not a client error.
            throw new IllegalStateException("JSON codec cannot decode type " + type.getName(), unsupported);
        } catch (UnrecognizedPropertyException unknown) {
            var path = unknown.getPath();
            throw decodeFailure("unknown_field", path.subList(0, Math.max(0, path.size() - 1)));
        } catch (ValueInstantiationException invalid) {
            throw decodeFailure("invalid_value", invalid.getPath());
        } catch (MismatchedInputException mismatch) {
            if (limitExceeded(mismatch)) { throw new DecodeException("limit_exceeded"); }
            if (isTrailing(mismatch)) { throw new DecodeException("trailing_content"); }
            throw decodeFailure("type_mismatch", mismatch.getPath());
        } catch (JsonMappingException mapping) {
            if (limitExceeded(mapping)) { throw new DecodeException("limit_exceeded"); }
            throw decodeFailure("invalid_value", mapping.getPath());
        } catch (JsonProcessingException malformed) {
            throw new DecodeException(isDuplicate(malformed) ? "duplicate_field" : "malformed_json");
        } catch (IOException unreadable) {
            throw new DecodeException("malformed_json");
        }
    }

    /** Databind may wrap a constraint violation found while deserializing a value. */
    private static boolean limitExceeded(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StreamConstraintsException) { return true; }
        }
        return false;
    }

    private static boolean isTrailing(MismatchedInputException mismatch) {
        var message = mismatch.getOriginalMessage();
        return message != null && message.startsWith("Trailing token");
    }

    private static boolean isDuplicate(JsonProcessingException failure) {
        var message = failure.getOriginalMessage();
        return message != null && message.startsWith("Duplicate field");
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
