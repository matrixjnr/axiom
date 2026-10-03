package io.axiom.validation;

import io.axiom.codec.spi.BodyCodec;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Test codec decoding {@code name|email} bodies into signups, so ctx.validatedBody runs without JSON. */
public final class SignupCodec implements BodyCodec {
    /** Media type handled by this codec. */
    public static final String MEDIA_TYPE = "text/x-signup";

    /** Creates the codec. */
    public SignupCodec() { }

    @Override public Set<String> mediaTypes() { return Set.of(MEDIA_TYPE); }

    @Override public <T> T decode(byte[] content, Class<T> type) {
        return type.cast(ValidationEndToEndTest.Signup.parse(content));
    }

    @Override public byte[] encode(Object value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
}
