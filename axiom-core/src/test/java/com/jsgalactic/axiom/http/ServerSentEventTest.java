package com.jsgalactic.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerSentEventTest {
    /** Collects what a sink writes, one entry per write. */
    private static final class Capture implements BodyWriter {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int writes;
        @Override public void write(byte[] source, int offset, int length) {
            bytes.write(source, offset, length);
            writes++;
        }
        @Override public long bytesWritten() { return bytes.size(); }
        String text() { return bytes.toString(StandardCharsets.UTF_8); }
    }

    private static String sent(EventBody body) throws Exception {
        var capture = new Capture();
        body.run(EventSink.of(capture));
        return capture.text();
    }

    private interface EventBody { void run(EventSink sink) throws Exception; }

    @Test void framesNameIdRetryAndDataThenABlankLine() throws Exception {
        var event = ServerSentEvent.named("update", "{\"n\":1}").withId("42").withRetry(Duration.ofSeconds(3));
        assertThat(sent(sink -> sink.send(event)))
                .isEqualTo("event: update\nid: 42\nretry: 3000\ndata: {\"n\":1}\n\n");
    }

    @Test void aDataOnlyEventHasNoOtherFieldAndEmptyDataStillSendsALine() throws Exception {
        assertThat(sent(sink -> sink.send("hello"))).isEqualTo("data: hello\n\n");
        assertThat(sent(sink -> sink.send(""))).isEqualTo("data: \n\n");
    }

    @Test void eachEventIsOneWrite() throws Exception {
        var capture = new Capture();
        var sink = EventSink.of(capture);
        sink.send(ServerSentEvent.data("a\nb"));
        sink.comment("x");
        sink.keepAlive();
        assertThat(capture.writes).isEqualTo(3);
        assertThat(sink.bytesWritten()).isEqualTo(capture.bytes.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\rb", "a\r\nb"})
    void everyLineBreakInDataStartsAnotherDataLine(String data) throws Exception {
        assertThat(sent(sink -> sink.send(data))).isEqualTo("data: a\ndata: b\n\n");
    }

    @Test void dataCannotInjectFields() throws Exception {
        var hostile = "ok\r\nevent: admin\r\nid: 1\rretry: 1\n\ndata: forged";
        var wire = sent(sink -> sink.send(hostile));
        // Every line the client sees is a data line; the blank line that ends the event comes only at the end.
        assertThat(wire).isEqualTo("data: ok\ndata: event: admin\ndata: id: 1\ndata: retry: 1\ndata: \ndata: data: forged\n\n");
        assertThat(wire.substring(0, wire.length() - 2).lines()).allMatch(line -> line.startsWith("data: "));
        assertThat(wire.indexOf("\n\n")).isEqualTo(wire.length() - 2);
    }

    @Test void trailingLineBreaksAreKeptAsEmptyLines() throws Exception {
        assertThat(sent(sink -> sink.send("a\n"))).isEqualTo("data: a\ndata: \n\n");
        assertThat(sent(sink -> sink.send("\n"))).isEqualTo("data: \ndata: \n\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a\nb", "a\rb", "a\r\nb", "\n", "x\ndata: forged"})
    void aNameWithALineBreakOrNothingIsRejected(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> ServerSentEvent.named(name, "d"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\rb", "a\r\nretry: 1", "a\0b"})
    void anIdWithALineBreakOrNulIsRejected(String id) {
        assertThatIllegalArgumentException().isThrownBy(() -> ServerSentEvent.data("d").withId(id));
    }

    @Test void anEmptyIdIsAllowedAndResetsTheClientsLastId() throws Exception {
        assertThat(sent(sink -> sink.send(ServerSentEvent.data("d").withId("")))).isEqualTo("id: \ndata: d\n\n");
    }

    @Test void retryMustBeAWholePositiveNumberOfMillisecondsThatFitsAnInt() {
        var event = ServerSentEvent.data("d");
        assertThatIllegalArgumentException().isThrownBy(() -> event.withRetry(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> event.withRetry(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> event.withRetry(Duration.ofNanos(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> event.withRetry(Duration.ofMillis(Integer.MAX_VALUE + 1L)));
        assertThat(event.withRetry(Duration.ofMillis(Integer.MAX_VALUE)).retry()).isEqualTo(Duration.ofMillis(Integer.MAX_VALUE));
    }

    @Test void commentsAreOneCommentLinePerLineAndCannotInjectFields() throws Exception {
        assertThat(sent(sink -> sink.comment("hi"))).isEqualTo(": hi\n\n");
        assertThat(sent(sink -> sink.comment("a\r\nevent: x\rid: 1\nretry: 1")))
                .isEqualTo(": a\n: event: x\n: id: 1\n: retry: 1\n\n");
        assertThat(sent(sink -> sink.comment(""))).isEqualTo(": \n\n");
        assertThat(sent(EventSink::keepAlive)).isEqualTo(": keep-alive\n\n");
    }

    @Test void nonAsciiDataIsUtf8() throws Exception {
        var capture = new Capture();
        EventSink.of(capture).send("é");
        assertThat(capture.bytes.toByteArray()).isEqualTo("data: é\n\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test void eventsAreValuesAndExposeTheirParts() {
        var event = ServerSentEvent.named("n", "d").withId("1").withRetry(Duration.ofMillis(5));
        assertThat(event.name()).isEqualTo("n");
        assertThat(event.id()).isEqualTo("1");
        assertThat(event.retry()).isEqualTo(Duration.ofMillis(5));
        assertThat(event.data()).isEqualTo("d");
        assertThat(event).isEqualTo(ServerSentEvent.named("n", "d").withId("1").withRetry(Duration.ofMillis(5)))
                .hasSameHashCodeAs(ServerSentEvent.named("n", "d").withId("1").withRetry(Duration.ofMillis(5)))
                .isNotEqualTo(ServerSentEvent.named("n", "d"));
        assertThat(ServerSentEvent.data("d").name()).isNull();
        assertThatNullPointerException().isThrownBy(() -> ServerSentEvent.data(null));
        assertThatNullPointerException().isThrownBy(() -> ServerSentEvent.named(null, "d"));
    }

    @Test void sseResponsesDisableCachingTransformationAndProxyBuffering() throws Exception {
        var response = Response.sse(sink -> sink.send("x"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isStreaming()).isTrue();
        assertThat(response.streamLimit()).isEqualTo(Response.DEFAULT_STREAM_LIMIT);
        assertThat(response.headers()).containsEntry("Content-Type", "text/event-stream")
                .containsEntry("Cache-Control", "no-store, no-transform").containsEntry("X-Accel-Buffering", "no")
                .doesNotContainKey("Content-Encoding").doesNotContainKey("Content-Length");
        var capture = new Capture();
        response.streamBody().writeTo(capture);
        assertThat(capture.text()).isEqualTo("data: x\n\n");
        assertThat(Response.sse(10, sink -> { }).streamLimit()).isEqualTo(10);
        assertThatIllegalArgumentException().isThrownBy(() -> Response.sse(0, sink -> { }));
    }

    @Test void writeFailuresReachTheEventBody() {
        BodyWriter closed = new BodyWriter() {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                throw new StreamAbortedException(StreamAbortedException.Reason.CLIENT_DISCONNECTED);
            }
            @Override public long bytesWritten() { return 0; }
        };
        var sink = EventSink.of(closed);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sink.keepAlive()).isInstanceOf(StreamAbortedException.class);
    }
}
