package io.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BodyTest {
    @Test void copiesOnCreationAndOnEveryRead() {
        var source = "secret".getBytes(StandardCharsets.UTF_8);
        var body = Body.of("text/plain", source);
        source[0] = 'X';
        var read = body.bytes();
        read[1] = 'X';
        assertThat(new String(body.bytes(), StandardCharsets.UTF_8)).isEqualTo("secret");
        assertThat(body.asReadOnlyBuffer().isReadOnly()).isTrue();
        assertThat(body.length()).isEqualTo(6);
    }

    @Test void concatenatesBuffersOnceWithoutMovingThem() {
        var first = ByteBuffer.wrap(new byte[] {1, 2});
        var second = ByteBuffer.wrap(new byte[] {9, 3, 4}).position(1);
        var body = Body.of(null, first, second);
        assertThat(body.bytes()).containsExactly(1, 2, 3, 4);
        assertThat(first.position()).isZero();
        assertThat(second.position()).isEqualTo(1);
        assertThat(body.contentType()).isEmpty();
    }

    @Test void parsesMediaTypeAndCharsetWithoutTrustingFormat() {
        var body = Body.of("Application/JSON ; Charset=\"UTF-8\"", new byte[] {1});
        assertThat(body.mediaType()).contains("application/json");
        assertThat(body.charset()).contains("utf-8");
        assertThat(Body.of("not a type", new byte[0]).mediaType()).isEmpty();
        assertThat(Body.of("application/json", new byte[0]).charset()).isEmpty();
        assertThat(Body.empty().isEmpty()).isTrue();
        assertThat(Body.empty().mediaType()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> Body.of("text/plain\r\nX: y", new byte[0]));
    }

    @Test void comparesByValueAndDescribesWithoutContent() {
        var body = Body.of("application/json", "{\"password\":\"hunter2\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(body).isEqualTo(Body.of("application/json", body.bytes())).hasSameHashCodeAs(
                Body.of("application/json", body.bytes()));
        assertThat(body).isNotEqualTo(Body.of("text/plain", body.bytes()));
        assertThat(body.toString()).doesNotContain("hunter2").contains("length=22", "application/json");
    }
}
