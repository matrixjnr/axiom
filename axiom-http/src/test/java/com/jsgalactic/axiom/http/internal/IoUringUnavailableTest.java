package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.lifecycle.TransportKind;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * io_uring is compile-time only in the ordinary test class path, so these cases run
 * deterministically without a particular kernel. They pin the rule that an explicit native kind is
 * never silently replaced: resolution fails, and listener startup fails before a thread or socket
 * exists.
 */
class IoUringUnavailableTest {
  private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);

  @Test
  void autoDoesNotSelectAnUnusableIoUring() {
    Assumptions.assumeTrue(
        NativeTransports.ioUring().transport() == null, "io_uring is usable on this class path");
    assertThat(Transport.resolve(TransportKind.AUTO).kind()).isNotEqualTo(TransportKind.IO_URING);
  }

  @Test
  void explicitIoUringFailsResolutionAndNamesTheArtifact() {
    Assumptions.assumeTrue(
        NativeTransports.ioUring().transport() == null, "io_uring is usable on this class path");
    assertThatThrownBy(() -> Transport.resolve(TransportKind.IO_URING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IO_URING")
        .hasMessageContaining("netty-transport-native-io_uring");
  }

  @Test
  void explicitIoUringFailsStartupBeforeAnySocketExists() {
    Assumptions.assumeTrue(
        NativeTransports.ioUring().transport() == null, "io_uring is usable on this class path");
    var options = ListenerOptions.builder().transport(TransportKind.IO_URING).build();
    try (var app = Axiom.create()) {
      assertThatThrownBy(() -> NettyServer.bind(app, LOOPBACK, TransportSettings.of(options)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("IO_URING")
          .hasMessageContaining("netty-transport-native-io_uring");
    }
  }
}
