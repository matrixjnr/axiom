package com.jsgalactic.axiom.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import example.modules.GrpcExample;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The example service over an in-process channel: no socket, no generated stub, no sleep. */
class GrpcExampleTest {
  @Test
  void answersAUnaryCallAndDrainsWhenTheApplicationCloses() throws Exception {
    var name = InProcessServerBuilder.generateName();
    var app = Axiom.create();
    // The example's transport hook is package-private, so this test lives beside the adapter.
    var grpc =
        Grpc.builder(app)
            .service(new GrpcExample.EchoService())
            .transport(InProcessServerBuilder.forName(name))
            .drainGrace(Duration.ofSeconds(2))
            .build();
    grpc.start();
    assertThat(grpc.isRunning()).isTrue();

    ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    try {
      assertThat(
              ClientCalls.blockingUnaryCall(channel, GrpcExample.ECHO, CallOptions.DEFAULT, "hi"))
          .isEqualTo("echo:hi");
    } finally {
      channel.shutdownNow();
    }

    app.close();
    assertThat(grpc.awaitTermination(Duration.ofSeconds(2))).isTrue();
    assertThat(grpc.isRunning()).isFalse();
  }
}
