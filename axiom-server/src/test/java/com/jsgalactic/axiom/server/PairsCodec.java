package com.jsgalactic.axiom.server;

import com.jsgalactic.axiom.codec.spi.BodyCodec;
import com.jsgalactic.axiom.error.DecodeException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Test codec claiming application/json with a toy {@code key=value;key=value} format, so server
 * tests exercise codec discovery and mapping without a serialization dependency. Supports
 * records whose components are String or int.
 */
public final class PairsCodec implements BodyCodec {
    /** Creates the codec. */
    public PairsCodec() { }

    @Override public Set<String> mediaTypes() { return Set.of("application/json"); }

    @Override public <T> T decode(byte[] content, Class<T> type) {
        var text = new String(content, StandardCharsets.UTF_8);
        if (text.equals("null")) { return null; }
        if (!type.isRecord()) { throw new DecodeException("unsupported_type"); }
        var values = new HashMap<String, String>();
        for (var pair : text.split(";")) {
            int equals = pair.indexOf('=');
            if (equals < 1) { throw new DecodeException("malformed_body"); }
            values.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        var components = type.getRecordComponents();
        var arguments = new Object[components.length];
        var types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            var component = components[i];
            types[i] = component.getType();
            var value = values.remove(component.getName());
            if (value == null) { throw new DecodeException("missing_field", component.getName()); }
            try {
                arguments[i] = component.getType() == int.class ? Integer.parseInt(value) : value;
            } catch (NumberFormatException wrong) { throw new DecodeException("type_mismatch", component.getName()); }
        }
        if (!values.isEmpty()) { throw new DecodeException("unknown_field"); }
        try {
            return type.getDeclaredConstructor(types).newInstance(arguments);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    @Override public byte[] encode(Object value) {
        if (!value.getClass().isRecord()) { throw new IllegalStateException("Only records are supported"); }
        var joiner = new StringJoiner(";");
        try {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                joiner.add(component.getName() + "=" + component.getAccessor().invoke(value));
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        return joiner.toString().getBytes(StandardCharsets.UTF_8);
    }
}
