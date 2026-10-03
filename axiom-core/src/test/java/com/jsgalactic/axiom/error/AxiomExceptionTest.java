package com.jsgalactic.axiom.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class AxiomExceptionTest {
    @Test void mapsTheTaxonomyToStatusesAndSafeCodes() {
        assertThat(new DecodeException("malformed_body").status()).isEqualTo(400);
        assertThat(new PayloadTooLargeException().status()).isEqualTo(413);
        assertThat(new UnsupportedMediaTypeException().code()).isEqualTo("unsupported_media_type");
        assertThat(new UnsupportedMediaTypeException("missing_content_type").status()).isEqualTo(415);
        var validation = new ValidationException(List.of(new Violation("items[0].name", "required")));
        assertThat(validation.status()).isEqualTo(422);
        assertThat(validation.code()).isEqualTo("validation_failed");
        assertThat(validation.violations()).containsExactly(new Violation("items[0].name", "required"));
        var decode = new DecodeException("type_mismatch", "age");
        assertThat(decode.field()).contains("age");
        assertThat(decode.violations()).containsExactly(new Violation("age", "type_mismatch"));
        assertThat(new DecodeException("empty_body").violations()).isEmpty();
    }

    @Test void refusesCodesAndFieldsThatCouldCarryInput() {
        for (var code : new String[] {"", "Upper", "has space", "<script>", "x".repeat(65), "1digit", "a\nb"}) {
            assertThatIllegalArgumentException().as(code).isThrownBy(() -> new DecodeException(code));
        }
        for (var field : new String[] {"", "a b", "a..b", "a[x]", "\"quoted\"", "x".repeat(257), "a/b"}) {
            assertThatIllegalArgumentException().as(field).isThrownBy(() -> new Violation(field, "invalid"));
            assertThatIllegalArgumentException().as(field).isThrownBy(() -> new DecodeException("invalid", field));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new ValidationException(
                java.util.Collections.nCopies(101, new Violation("a", "b"))));
    }

    @Test void messagesContainOnlyStatusAndCode() {
        var failure = new DecodeException("malformed_body", "name");
        failure.initCause(new IllegalStateException("POISON input"));
        assertThat(failure.getMessage()).isEqualTo("400 malformed_body");
        assertThat(new PayloadTooLargeException().getMessage()).isEqualTo("413 content_too_large");
    }
}
