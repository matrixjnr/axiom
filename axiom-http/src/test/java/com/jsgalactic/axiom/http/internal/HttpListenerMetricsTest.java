package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Listener-level measurements over real sockets: connections, bytes, answers given without
 * admission, and the listener tag that tells listeners of one application apart. Waiting is for a
 * measurement, never for a fixed time.
 */
@Tag("integration")
class HttpListenerMetricsTest {
    private static com.jsgalactic.axiom.lifecycle.Server listen(Fixture fixture, String name, int maxConnections)
            throws Exception {
        var options = ListenerOptions.builder().name(name).maxConnections(maxConnections).build();
        var server = fixture.app.listen(new InetSocketAddress("127.0.0.1", 0), options);
        fixture.servers.add(server);
        return server;
    }

    @Test void countsConnectionsBytesAndAnswersPerNamedListener() throws Exception {
        var probe = new Probe();
        try (var fixture = new Fixture()) {
            fixture.app.metrics(probe);
            fixture.app.get("/hi", ctx -> "hello");
            var api = listen(fixture, "api", 8);
            var admin = listen(fixture, "admin", 8);
            var request = "GET /hi HTTP/1.1\r\nHost: localhost\r\n\r\n"; // As Wire.get sends it.
            try (var wire = new Wire(api)) {
                assertThat(wire.get("/hi").text()).isEqualTo("hello");
                probe.await(v -> v == 1, ListenerMetrics.ACCEPTED, "listener", "api");
                probe.await(v -> v == 1, ConnectionSlots.CONNECTIONS, "listener", "api", "state", "open");
                // Every byte the client sent was counted, and at least the body of the answer went out.
                probe.await(v -> v == request.getBytes(StandardCharsets.US_ASCII).length, ListenerMetrics.BYTES,
                        "listener", "api", "direction", "in");
                probe.await(v -> v > "hello".length(), ListenerMetrics.BYTES, "listener", "api", "direction", "out");
                // A request the listener cannot parse is answered by the listener itself, before admission.
                wire.write("GET /x HTTP/1.1\r\nHost: a\r\nContent-Length: abc\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(400);
                probe.await(v -> v == 1, ListenerMetrics.ANSWERS, "listener", "api", "status", "400");
            }
            probe.await(v -> v == 0, ConnectionSlots.CONNECTIONS, "listener", "api", "state", "open");
            try (var wire = new Wire(admin)) {
                assertThat(wire.get("/hi").text()).isEqualTo("hello");
                probe.await(v -> v == 1, ListenerMetrics.ACCEPTED, "listener", "admin");
            }
            // The other listener's series did not move.
            assertThat(probe.value(ListenerMetrics.ACCEPTED, "listener", "api")).isEqualTo(1);
            assertThat(probe.value(ListenerMetrics.ANSWERS, "listener", "admin", "status", "400")).isZero();
            assertThat(probe.tagValues()).contains(ListenerMetrics.ACCEPTED + ":listener=api",
                    ListenerMetrics.ACCEPTED + ":listener=admin");
        }
    }

    @Test void countsConnectionsTurnedAwayAtTheLimit() throws Exception {
        var probe = new Probe();
        try (var fixture = new Fixture()) {
            fixture.app.metrics(probe);
            fixture.app.get("/hi", ctx -> "hello");
            var server = listen(fixture, "tiny", 1);
            try (var first = new Wire(server)) {
                assertThat(first.get("/hi").text()).isEqualTo("hello");
                try (var second = new Wire(server)) {
                    assertThat(second.socket.isConnected()).isTrue();
                    probe.await(v -> v == 1, ListenerMetrics.REJECTED, "listener", "tiny", "reason", "limit");
                }
                assertThat(probe.value(ListenerMetrics.ACCEPTED, "listener", "tiny")).isEqualTo(1);
            }
        }
    }

    @Test void listenerNamesAreValidatedAndDefaultToDefault() {
        assertThat(ListenerOptions.defaults().name()).isEqualTo("default");
        assertThat(ListenerOptions.builder().name("admin_2").build().toBuilder().build().name()).isEqualTo("admin_2");
        for (var invalid : new String[] {"", "Admin", "1a", "a-b", "a b", "x".repeat(33)}) {
            assertThatIllegalArgumentException().as(invalid).isThrownBy(() -> ListenerOptions.builder().name(invalid));
        }
    }

    @Test void noMeasurementsAreTakenWithoutMetrics() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/hi", ctx -> "hello");
            try (var wire = new Wire(fixture.listen())) {
                assertThat(wire.get("/hi").text()).isEqualTo("hello");
            }
        }
    }
}
