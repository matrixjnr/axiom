package com.jsgalactic.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultipartParserTest {
    private static final String CONTENT_TYPE = "multipart/form-data; boundary=----axiom";
    private static final String BOUNDARY = "------axiom";

    @TempDir Path directory;

    @Test void deliversEachPartWithItsHeadersAndContent() throws Exception {
        var body = BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "hello\r\n"
                + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"upload\"; filename=\"a.txt\"\r\n\r\n"
                + "body bytes\r\n"
                + BOUNDARY + "--\r\n";
        var parts = new ArrayList<MultipartParser.Part>();
        var completed = new AtomicInteger();
        try (var parser = parser(body, MultipartParser.Limits.DEFAULT, parts::add,
                () -> completed.incrementAndGet())) {
            parser.parse();
        }
        assertThat(parts).hasSize(2);
        assertThat(parts.getFirst().name()).isEqualTo("title");
        assertThat(parts.getFirst().headers().get("content-type")).isEqualTo("text/plain");
        assertThat(parts.getFirst().text()).isEqualTo("hello");
        assertThat(parts.get(1).filename()).isEqualTo("a.txt");
        assertThat(parts.get(1).size()).isEqualTo(10);
        assertThat(completed).hasValue(1);
        assertThat(Files.list(directory)).isEmpty();
    }

    @Test void spillsLargePartsAndDeletesTemporaryFilesOnClose() throws Exception {
        String data = "x".repeat(20_000);
        var body = BOUNDARY + "\r\nContent-Disposition: form-data; name=\"big\"\r\n\r\n"
                + data + "\r\n" + BOUNDARY + "--\r\n";
        var parts = new ArrayList<MultipartParser.Part>();
        var parser = parser(body, MultipartParser.Limits.DEFAULT, parts::add, () -> { });
        parser.parse();
        assertThat(Files.list(directory)).hasSize(1);
        assertThat(parts.getFirst().text()).hasSize(20_000);
        parser.close();
        assertThat(Files.list(directory)).isEmpty();
        parser.close(); // idempotent
    }

    @Test void enforcesPartCountPartSizeHeaderSizeAndTotalBytes() throws Exception {
        var two = BOUNDARY + "\r\n\r\na\r\n" + BOUNDARY + "\r\n\r\nb\r\n" + BOUNDARY + "--\r\n";
        assertLimit(two, new MultipartParser.Limits(1, 100, 100, 1000));

        var longPart = BOUNDARY + "\r\n\r\nabcdef\r\n" + BOUNDARY + "--\r\n";
        assertLimit(longPart, new MultipartParser.Limits(4, 3, 100, 1000));

        var longHeader = BOUNDARY + "\r\nX-Long: " + "h".repeat(60) + "\r\n\r\na\r\n" + BOUNDARY + "--\r\n";
        assertLimit(longHeader, new MultipartParser.Limits(4, 100, 32, 1000));

        var total = BOUNDARY + "\r\n\r\nabcde\r\n" + BOUNDARY + "--\r\n";
        assertLimit(total, new MultipartParser.Limits(4, 100, 100, 20));
    }

    @Test void rejectsMalformedBodiesWithoutLeakingFiles() throws Exception {
        assertMalformed("this is not multipart");
        assertMalformed(BOUNDARY + "\r\n\r\na\r\n" + BOUNDARY + "x\r\n");
        assertMalformed(BOUNDARY + "\r\nbad-header\r\n\r\na\r\n" + BOUNDARY + "--\r\n");
        assertMalformed(BOUNDARY + "\r\n\r\na\r\n");
        assertThat(Files.list(directory)).isEmpty();
    }

    @Test void aBoundaryLikeSequenceInsideDataIsNotADelimiter() throws Exception {
        var body = BOUNDARY + "\r\n\r\n" + "line\r\n" + BOUNDARY + "x\r\nmore\r\n"
                + BOUNDARY + "--\r\n";
        var parts = new ArrayList<MultipartParser.Part>();
        try (var parser = parser(body, MultipartParser.Limits.DEFAULT, parts::add, () -> { })) {
            parser.parse();
        }
        assertThat(parts).hasSize(1);
        assertThat(parts.getFirst().text()).isEqualTo("line\r\n" + BOUNDARY + "x\r\nmore");
    }

    @Test void closingTheParserReleasesSpilledStorageAfterAFailedParse() throws Exception {
        var body = BOUNDARY + "\r\n\r\n" + "x".repeat(20_000); // no closing boundary
        var parser = parser(body, MultipartParser.Limits.DEFAULT, part -> { }, () -> { });
        assertThatThrownBy(parser::parse).isInstanceOf(MultipartException.class)
                .hasFieldOrPropertyWithValue("code", MultipartException.MALFORMED);
        assertThat(Files.list(directory)).hasSize(1);
        parser.close();
        assertThat(Files.list(directory)).isEmpty();
    }

    private void assertLimit(String body, MultipartParser.Limits limits) throws Exception {
        var parser = parser(body, limits, part -> { }, () -> { });
        assertThatThrownBy(parser::parse).isInstanceOf(MultipartException.class)
                .hasFieldOrPropertyWithValue("code", MultipartException.LIMIT_EXCEEDED);
        parser.close();
        assertThat(Files.list(directory)).as("temporary files after a limit").isEmpty();
    }

    private void assertMalformed(String body) throws Exception {
        var parser = parser(body, MultipartParser.Limits.DEFAULT, part -> { }, () -> { });
        assertThatThrownBy(parser::parse).isInstanceOf(MultipartException.class)
                .hasFieldOrPropertyWithValue("code", MultipartException.MALFORMED);
        parser.close();
    }

    private MultipartParser parser(String body, MultipartParser.Limits limits,
                                   MultipartParser.Listener listener, Runnable complete) {
        var source = BodyReader.of(body.getBytes(StandardCharsets.UTF_8));
        return new MultipartParser(source, CONTENT_TYPE, limits, new MultipartParser.Listener() {
            @Override public void part(MultipartParser.Part part) throws IOException { listener.part(part); }

            @Override public void complete() { complete.run(); }
        }, directory);
    }
}
