package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.server.internal.ResponseSerialization;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Checks over a socket that the listener applies the shared serialization rules: it answers 500
 * for exactly the boundary responses they refuse, as the test client does, so wiring the
 * transport to anything other than those rules fails here.
 */
class ResponseSerializationAgreementTest {
    private static final int MIB = 1024 * 1024;

    @Test void listenerRefusesExactlyWhatTheSharedRulesRefuse() throws Exception {
        var cases = new LinkedHashMap<String, Supplier<Object>>();
        cases.put("/text-at-limit", () -> "x".repeat(MIB));
        cases.put("/text-over", () -> "x".repeat(MIB + 1));
        cases.put("/wide-at-limit", () -> "é".repeat(MIB / 2));
        cases.put("/wide-over", () -> "é".repeat(MIB / 2 + 1));
        cases.put("/bytes-at-limit", () -> new byte[MIB]);
        cases.put("/bytes-over", () -> new byte[MIB + 1]);
        cases.put("/object", () -> new StringBuilder("x"));
        cases.put("/headers-at-limit", () -> Response.of(204, null).withHeader("A", "v".repeat(8192 - 5)));
        cases.put("/headers-over", () -> Response.of(204, null).withHeader("A", "v".repeat(8192 - 4)));
        cases.put("/latin1", () -> Response.of(204, null).withHeader("A", "ÿ"));
        cases.put("/not-latin1", () -> Response.of(204, null).withHeader("A", "Ā"));
        try (var fixture = new Fixture()) {
            cases.forEach((path, value) -> fixture.app.get(path, ctx -> value.get()));
            var server = fixture.listen();
            for (var path : cases.keySet()) {
                var prepared = fixture.app.handle(Request.get(path));
                boolean refused = ResponseSerialization.rejection(prepared) != null;
                try (var wire = new Wire(server)) {
                    wire.write("GET " + path + " HTTP/1.1\r\nHost: a\r\n\r\n");
                    int status = wire.read(false).status();
                    assertThat(status == 500).as(path + " answered " + status).isEqualTo(refused);
                }
            }
        }
    }
}
