package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The connection's peer reaches handlers as the request's remote address. */
@Tag("integration")
class HttpRemoteAddressTest {
    @Test void exposesTheSocketPeerAndIgnoresForwardingHeaders() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/peer", ctx -> {
                var peer = ctx.request().remoteAddress();
                return peer.getAddress().getHostAddress() + " " + (peer.getPort() > 0);
            });
            try (var wire = new Wire(fixture.listen())) {
                wire.write("GET /peer HTTP/1.1\r\nHost: a\r\nX-Forwarded-For: 203.0.113.9\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("127.0.0.1 true");
            }
        }
    }
}
