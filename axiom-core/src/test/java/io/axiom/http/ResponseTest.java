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
    void comparesByValueWithByteArraysByContentAndHeaderNamesIgnoringCase() {
        var bytes = Response.of(200, new byte[] {1, 2}).withHeader("X-Test", "v");
        var sameBytes = Response.of(200, new byte[] {1, 2}).withHeader("x-test", "v");
        assertThat(bytes).isEqualTo(sameBytes).hasSameHashCodeAs(sameBytes);
        assertThat(bytes).isNotEqualTo(Response.of(200, new byte[] {1, 3}).withHeader("X-Test", "v"));
        assertThat(bytes).isNotEqualTo(Response.of(201, new byte[] {1, 2}).withHeader("X-Test", "v"));
        assertThat(bytes).isNotEqualTo(Response.of(200, new byte[] {1, 2}).withHeader("X-Test", "V"));
        assertThat(Response.of(200, "a")).isEqualTo(Response.of(200, "a")).isNotEqualTo(Response.of(200, "b"));
        assertThat(Response.of(204, null)).isEqualTo(Response.of(204, null)).isNotEqualTo(null);
        record Payload(String value) {}
        assertThat(Response.of(200, new Payload("x"))).isEqualTo(Response.of(200, new Payload("x")));
    }

    @Test
    void describesItselfWithoutDumpingLargeBodies() {
        assertThat(Response.of(201, "hi")).hasToString(
                "Response[status=201, headers={Content-Type=text/plain; charset=utf-8}, body=\"hi\"]");
        assertThat(Response.of(200, new byte[3]).toString()).endsWith("body=byte[3]]");
        assertThat(Response.of(200, "x".repeat(100)).toString()).contains("... (100 chars)");
        assertThat(Response.of(200, 42).toString()).endsWith("body=java.lang.Integer]");
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

    @org.junit.jupiter.api.Test
    void buildsRedirectsAndLocationsWithoutHeaderInjection() {
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            var redirect = Response.redirect(status, "/next?x=1");
            org.assertj.core.api.Assertions.assertThat(redirect.status()).isEqualTo(status);
            org.assertj.core.api.Assertions.assertThat(redirect.body()).isNull();
            org.assertj.core.api.Assertions.assertThat(redirect.headers()).containsEntry("Location", "/next?x=1");
        }
        var created = Response.of(201, "{}").withLocation("https://example.com/items/1");
        org.assertj.core.api.Assertions.assertThat(created.headers()).containsEntry("location", "https://example.com/items/1");
        for (int status : new int[] {200, 304, 300, 400}) {
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> Response.redirect(status, "/"));
        }
        for (var location : new String[] {"", "/a\r\nSet-Cookie: x=1", "/a\nb", "/a b", "/\"quoted\"", "/<x>",
                "/a\\b", "/caf\u00e9", "/bad%zz", "/" + "x".repeat(2048)}) {
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException().as(location)
                    .isThrownBy(() -> Response.redirect(302, location));
        }
    }
}
