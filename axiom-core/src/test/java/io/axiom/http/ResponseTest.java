package io.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResponseTest {
    @Test
    void ownsByteArraysAtConstructionAndOnRead() {
        byte[] source = {1, 2, 3};
        var response = Response.of(200, source);
        source[0] = 9;
        ((byte[]) response.body())[1] = 9;
        assertThat((byte[]) response.body()).containsExactly(1, 2, 3);
        assertThat(response.headers()).containsEntry("content-type", "application/octet-stream");
    }

    @Test
    void headersAreImmutableCaseInsensitiveSnapshots() {
        var original = Response.of(200, "hello");
        var updated = original.withHeader("content-type", "text/custom").withHeader("Location", "/next");
        assertThat(original.headers()).containsEntry("CONTENT-TYPE", "text/plain; charset=utf-8");
        assertThat(updated.headers()).hasSize(2).containsEntry("Content-Type", "text/custom");
        assertThatThrownBy(() -> updated.headers().put("X-Other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r", "\n", "\u0000", "\u007f"})
    void rejectsHeaderControlCharacters(String control) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Response.of(200, null).withHeader("X-Test", "a" + control + "b"));
    }

    @Test
    void rejectsInvalidHeaderNames() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Response.of(200, null).withHeader("Bad Name", "value"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 199, 600})
    void rejectsInvalidFinalStatuses(int status) {
        assertThatIllegalArgumentException().isThrownBy(() -> Response.of(status, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 205, 304})
    void bodylessStatusesRejectBodies(int status) {
        assertThatIllegalArgumentException().isThrownBy(() -> Response.of(status, "not allowed"));
        assertThat(Response.of(status, null).body()).isNull();
    }

    @Test
    void headCopyPreservesMetadataWithoutTheBody() {
        var response = Response.of(201, "hello").withHeader("X-Test", "value");
        var head = response.withoutBody();
        assertThat(head.status()).isEqualTo(201);
        assertThat(head.body()).isNull();
        assertThat(head.headers()).isEqualTo(response.headers());
        assertThat(response.body()).isEqualTo("hello");
    }
}
