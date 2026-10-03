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

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @Test void exposesTheTraceStateOfAValidTraceparentAndDropsAnInvalidOne() throws Exception {
        var app = Axiom.create();
        app.get("/state", ctx -> ctx.traceContext().map(trace -> trace.traceState().header()).orElse("none"));
        try (var client = TestClient.start(app)) {
            var base = new Request("GET", "/state");
            assertThat(client.execute(base.withHeaders(Map.of("traceparent", VALID, "tracestate", "congo=t61, rojo=00f"))).body())
                    .isEqualTo("congo=t61,rojo=00f");
            assertThat(client.execute(base.withHeaders(Map.of("traceparent", VALID, "tracestate", "Bad Key=1"))).body())
                    .isEqualTo("");
            assertThat(client.execute(base.withHeaders(Map.of("traceparent", "nope", "tracestate", "congo=t61"))).body())
                    .isEqualTo("none");
            assertThat(client.execute(base.withHeaders(Map.of("tracestate", "congo=t61"))).body()).isEqualTo("none");
        }
    }

    @Test void theResponseHeaderIsOptInAndOnlyForRequestsThatSentATraceparent() throws Exception {
        var plain = Axiom.create();
        plain.get("/x", ctx -> "ok");
        try (var client = TestClient.start(plain)) {
            var response = client.execute(new Request("GET", "/x").withHeaders(Map.of("traceparent", VALID)));
            assertThat(response.headers()).doesNotContainKey("traceresponse");
        }
        var traced = Axiom.create();
        traced.use(com.jsgalactic.axiom.observability.TraceContext.responseHeader());
        traced.get("/x", ctx -> "ok");
        try (var client = TestClient.start(traced)) {
            var response = client.execute(new Request("GET", "/x").withHeaders(Map.of("traceparent", VALID)));
            assertThat(response.headers().get("traceresponse")).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01")
                    .isNotEqualTo(VALID);
            assertThat(client.get("/x").headers()).doesNotContainKey("traceresponse");
            var bad = client.execute(new Request("GET", "/x").withHeaders(Map.of("traceparent", "garbage")));
            assertThat(bad.headers()).doesNotContainKey("traceresponse");
        }
    }

    @Test void correlationNamesTheRequestIdAndTheTraceWhenThereIsOne() throws Exception {
        var app = Axiom.create();
        app.get("/c", ctx -> ctx.correlation() + "|" + ctx.execution().requestId());
        try (var client = TestClient.start(app)) {
            var traced = (String) client.execute(new Request("GET", "/c").withHeaders(Map.of("traceparent", VALID))).body();
            var parts = traced.split("\\|");
            assertThat(parts[0]).isEqualTo(parts[1] + " trace=" + TRACE_ID);
            var untraced = (String) client.get("/c").body();
            var plain = untraced.split("\\|");
            assertThat(plain[0]).isEqualTo(plain[1]);
        }
    }

    @Test void theFrameworksOwnLogMessagesCarryTheTraceIdNextToTheRequestId() throws Exception {
        var records = new java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>();
        var handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        var logger = java.util.logging.Logger.getLogger("com.jsgalactic.axiom.server.internal.DefaultApplication");
        logger.addHandler(handler);
        try {
            var app = Axiom.create();
            app.get("/boom", ctx -> { throw new com.jsgalactic.axiom.error.InternalServerErrorException("boom"); });
            try (var client = TestClient.start(app)) {
                assertThat(client.execute(new Request("GET", "/boom").withHeaders(Map.of("traceparent", VALID))).status())
                        .isEqualTo(500);
                assertThat(client.get("/boom").status()).isEqualTo(500);
            }
        } finally {
            logger.removeHandler(handler);
        }
        var messages = records.stream().map(java.util.logging.LogRecord::getMessage).toList();
        assertThat(messages).anyMatch(message -> message.contains(" trace=" + TRACE_ID + " failed with 500"));
        assertThat(messages).anyMatch(message -> !message.contains("trace=") && message.contains("failed with 500"));
    }
}
