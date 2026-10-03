package com.jsgalactic.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StreamResponseTest {
    private static final StreamBody NOTHING = out -> { };

    @Test
    void aStreamedResponseCarriesItsWriterCapAndContentType() {
        var response = Response.stream(200, "text/csv", 10, NOTHING);
        assertThat(response.isStreaming()).isTrue();
        assertThat(response.streamBody()).isSameAs(NOTHING);
        assertThat(response.body()).isSameAs(NOTHING);
        assertThat(response.streamLimit()).isEqualTo(10);
        assertThat(response.headers()).containsEntry("content-type", "text/csv");
        assertThat(response.toString()).contains("stream(limit=10)");
    }

    @Test
    void theDefaultCapIsBounded() {
        assertThat(Response.stream(200, "text/plain", NOTHING).streamLimit())
                .isEqualTo(Response.DEFAULT_STREAM_LIMIT).isEqualTo(64L * 1024 * 1024);
    }

    @Test
    void headersAndEqualityFollowTheSameWriter() {
        var response = Response.stream(200, "text/plain", NOTHING).withHeader("X-Name", "v");
        assertThat(response.isStreaming()).isTrue();
        assertThat(response.headers()).containsEntry("x-name", "v");
        assertThat(response).isEqualTo(Response.stream(200, "text/plain", NOTHING).withHeader("X-Name", "v"));
        assertThat(response).isNotEqualTo(Response.stream(200, "text/plain", out -> { }).withHeader("X-Name", "v"));
    }

    @Test
    void withoutBodyEndsTheStream() {
        var head = Response.stream(200, "text/plain", NOTHING).withoutBody();
        assertThat(head.isStreaming()).isFalse();
        assertThat(head.body()).isNull();
        assertThat(head.streamBody()).isNull();
        assertThat(head.streamLimit()).isZero();
        assertThat(head.headers()).containsEntry("Content-Type", "text/plain");
    }

    @Test
    void plainResponsesAreNotStreaming() {
        assertThat(Response.of(200, "x").isStreaming()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 205, 304, 100, 600})
    void statusesWithoutABodyAreRejected(int status) {
        assertThatIllegalArgumentException().isThrownBy(() -> Response.stream(status, "text/plain", NOTHING));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void aNonPositiveCapIsRejected(long cap) {
        assertThatIllegalArgumentException().isThrownBy(() -> Response.stream(200, "text/plain", cap, NOTHING));
    }

    @Test
    void rejectsAnInjectedContentType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Response.stream(200, "text/plain\r\nSet-Cookie: a=b", NOTHING));
    }

    @Test
    void rejectsMissingParts() {
        assertThatNullPointerException().isThrownBy(() -> Response.stream(200, "text/plain", null));
        assertThatNullPointerException().isThrownBy(() -> Response.stream(200, null, NOTHING));
    }

    @Test
    void aStreamBodyCannotBeSmuggledThroughOf() {
        assertThatIllegalArgumentException().isThrownBy(() -> Response.of(200, NOTHING));
    }

    @Test
    void writerDefaultsDelegateToTheSliceMethodAsUtf8() throws Exception {
        var sink = new ByteArrayOutputStream();
        BodyWriter writer = new BodyWriter() {
            @Override public void write(byte[] bytes, int offset, int length) { sink.write(bytes, offset, length); }
            @Override public long bytesWritten() { return sink.size(); }
        };
        writer.write("é");
        writer.write(new byte[] {1, 2});
        assertThat(sink.toByteArray()).containsExactly((byte) 0xC3, (byte) 0xA9, 1, 2);
        assertThat(writer.bytesWritten()).isEqualTo(4);
    }

    @Test
    void abortReasonsAreReported() {
        var failure = new StreamAbortedException(StreamAbortedException.Reason.LIMIT_EXCEEDED);
        assertThat(failure.reason()).isEqualTo(StreamAbortedException.Reason.LIMIT_EXCEEDED);
        assertThat(failure).isInstanceOf(java.io.IOException.class).hasMessageContaining("LIMIT_EXCEEDED");
    }
}
