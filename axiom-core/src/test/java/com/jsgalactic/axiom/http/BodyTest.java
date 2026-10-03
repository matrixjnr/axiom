package com.jsgalactic.axiom.http;

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

    @Test void builderFillsTheBodyArrayAndTrimsOnlyWhenNotFull() {
        var exact = Body.builder("application/octet-stream", 6)
                .write(ByteBuffer.wrap(new byte[] {1, 2, 3})).write(ByteBuffer.wrap(new byte[] {4, 5, 6})).build();
        assertThat(exact.bytes()).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(exact.mediaType()).contains("application/octet-stream");
        var source = ByteBuffer.wrap(new byte[] {7, 8});
        var trimmed = Body.builder(null, 16).write(source).build();
        assertThat(trimmed.bytes()).containsExactly(7, 8);
        assertThat(source.remaining()).isZero();
        var grown = Body.builder(null, 1).write(ByteBuffer.wrap(new byte[] {9})).capacity(3)
                .write(ByteBuffer.wrap(new byte[] {10, 11})).build();
        assertThat(grown.bytes()).containsExactly(9, 10, 11);
        assertThat(Body.builder(null, 0).build()).isEqualTo(Body.of(null, new byte[0]));
    }

    @Test void builderRefusesMisuseAndNeverExposesItsArray() {
        var builder = Body.builder("text/plain", 2);
        org.assertj.core.api.Assertions.assertThatExceptionOfType(java.nio.BufferOverflowException.class)
                .isThrownBy(() -> builder.write(ByteBuffer.wrap(new byte[3])));
        assertThat(builder.length()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> builder.write(ByteBuffer.wrap(new byte[2])).capacity(1));
        assertThatIllegalArgumentException().isThrownBy(() -> Body.builder(null, -1));
        var body = builder.build();
        assertThat(body.bytes()).containsExactly(0, 0);
        org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(builder::build);
        org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(() -> builder.write(ByteBuffer.allocate(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> Body.builder("text/plain\r\nX: y", 0).build());
    }

    @Test void allocationShowsWhichAccessesCopyTheContent() {
        var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        boolean measurable = threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled();
        if (Boolean.getBoolean("axiom.requireAllocationTests")) {
            assertThat(measurable).as("thread allocation measurement is required by axiom.requireAllocationTests").isTrue();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(measurable);
        int size = 4 * 1024 * 1024;
        var chunk = ByteBuffer.wrap(new byte[size]);
        // Warm up the paths so class loading is not measured.
        Body.builder(null, 8).write(ByteBuffer.wrap(new byte[8])).build().asReadOnlyBuffer();
        Body.of(null, new byte[8]).bytes();

        long start = threads.getCurrentThreadAllocatedBytes();
        var body = Body.builder("application/octet-stream", size).write(chunk).build();
        long built = threads.getCurrentThreadAllocatedBytes() - start;

        start = threads.getCurrentThreadAllocatedBytes();
        int length = body.length();
        long view = body.asReadOnlyBuffer().remaining();
        long viewAllocated = threads.getCurrentThreadAllocatedBytes() - start;

        start = threads.getCurrentThreadAllocatedBytes();
        int copied = body.bytes().length;
        long copyAllocated = threads.getCurrentThreadAllocatedBytes() - start;

        assertThat(length).isEqualTo(size);
        assertThat(view).isEqualTo(size);
        assertThat(copied).isEqualTo(size);
        // The builder's own array is the only allocation of that size: no second copy on build.
        assertThat(built).as("building a %d-byte body", size).isBetween((long) size, size + 4096L);
        assertThat(viewAllocated).as("reading a view").isLessThan(1024);
        assertThat(copyAllocated).as("bytes() copies the content on every call").isGreaterThanOrEqualTo(size);
    }
}
