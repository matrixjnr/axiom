package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Request;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceContextTest {
    private static final String VALID = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    private static Object call(String header) throws Exception {
        var app = Axiom.create();
        app.get("/trace", ctx -> ctx.traceContext().map(trace -> trace.traceId() + "/" + trace.parentId())
                .orElse("none"));
        try (var client = TestClient.start(app)) {
            var request = new Request("GET", "/trace");
            var response = client.execute(header == null ? request : request.withHeaders(Map.of("TraceParent", header)));
            assertThat(response.status()).isEqualTo(200);
            return response.body();
        }
    }

    @Test void exposesAValidTraceparentOnTheContextWhateverTheHeaderCase() throws Exception {
        assertThat(call(VALID)).isEqualTo("0af7651916cd43dd8448eb211c80319c/b7ad6b7169203331");
    }

    @Test void ignoresAbsentAndMalformedHeadersWithoutFailingTheRequest() throws Exception {
        assertThat(call(null)).isEqualTo("none");
        assertThat(call("not-a-traceparent")).isEqualTo("none");
        assertThat(call(VALID.toUpperCase(java.util.Locale.ROOT))).isEqualTo("none");
        assertThat(call(VALID + "-extra")).isEqualTo("none");
    }

    @Test void theRequestIdStaysFrameworkGeneratedAndIsNotTheTraceId() throws Exception {
        var app = Axiom.create();
        app.get("/ids", ctx -> ctx.execution().requestId());
        try (var client = TestClient.start(app)) {
            var response = client.execute(new Request("GET", "/ids").withHeaders(Map.of("traceparent", VALID)));
            assertThat((String) response.body()).doesNotContain("0af7651916cd43dd8448eb211c80319c");
        }
    }
}
