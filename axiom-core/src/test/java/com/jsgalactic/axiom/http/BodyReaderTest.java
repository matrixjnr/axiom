package com.jsgalactic.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BodyReaderTest {
  @Test
  void handsOffEveryByteThenEnds() throws Exception {
    try (var reader = BodyReader.of("abc".getBytes(StandardCharsets.UTF_8))) {
      var bytes = new byte[2];
      assertThat(reader.read(bytes)).isEqualTo(2);
      assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("ab");
      assertThat(reader.read(bytes)).isEqualTo(1);
      assertThat(bytes[0]).isEqualTo((byte) 'c');
      assertThat(reader.bytesRead()).isEqualTo(3);
      assertThat(reader.read(bytes)).isEqualTo(-1);
      assertThat(reader.paused()).isFalse();
    }
  }

  @Test
  void anEmptyBodyEndsAtOnce() throws Exception {
    try (var reader = BodyReader.of(new byte[0])) {
      assertThat(reader.read(new byte[4])).isEqualTo(-1);
    }
  }

  @Test
  void pauseStopsAReadUntilResume() throws Exception {
    var reader = BodyReader.of("hi".getBytes(StandardCharsets.UTF_8));
    var entered = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var result = new AtomicInteger(-2);
    reader.pause();
    var handler =
        Thread.ofVirtual()
            .start(
                () -> {
                  entered.countDown();
                  try {
                    result.set(reader.read(new byte[4]));
                  } catch (Exception failure) {
                    throw new RuntimeException(failure);
                  } finally {
                    finished.countDown();
                  }
                });
    assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(finished.await(50, TimeUnit.MILLISECONDS)).as("read waits while paused").isFalse();
    assertThat(reader.paused()).isTrue();
    reader.resume();
    assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
    handler.join();
    assertThat(result).hasValue(2);
    assertThat(reader.paused()).isFalse();
    reader.close();
  }

  @Test
  void closeUnblocksAWaitingReadWithCancellation() throws Exception {
    var reader = BodyReader.of(new byte[0]);
    var entered = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    reader.pause();
    var handler =
        Thread.ofVirtual()
            .start(
                () -> {
                  entered.countDown();
                  try {
                    reader.read(new byte[4]);
                  } catch (Throwable thrown) {
                    failure.set(thrown);
                  }
                });
    assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
    reader.close();
    handler.join(30_000);
    assertThat(failure.get()).isInstanceOf(BodyReadException.class);
    assertThat(((BodyReadException) failure.get()).reason())
        .isEqualTo(BodyReadException.Reason.CANCELLED);
    assertThatThrownBy(() -> reader.read(new byte[4])).isInstanceOf(BodyReadException.class);
  }
}
