package com.jsgalactic.axiom.otel;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.error.BadRequestException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.observability.TraceContext;
import com.jsgalactic.axiom.observability.TraceState;
import com.jsgalactic.axiom.test.TestClient;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TracingTest {
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void createsAServerSpanParentedToTheW3cHeaders() throws Exception {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        var app = Axiom.create();
        app.use(Tracing.create(OpenTelemetrySdk.builder().setTracerProvider(provider).build()));
        app.get("/items/:id", ctx -> ctx.text("ok"));

        try (var client = TestClient.start(app)) {
            var response = client.execute(Request.get("/items/42").withHeaders(Map.of(
                    TraceContext.HEADER, TRACEPARENT, TraceState.HEADER, "vendor=value")));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headers()).doesNotContainKey(TraceContext.RESPONSE_HEADER);
        }

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        var span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("GET /items/:id");
        assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(span.getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(span.getParentSpanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(span.getParentSpanContext().isRemote()).isTrue();
        assertThat(span.getSpanContext().getTraceState().get("vendor")).isEqualTo("value");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("http.request.method"))).isEqualTo("GET");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("http.route"))).isEqualTo("/items/:id");
        assertThat(span.getAttributes().get(AttributeKey.longKey("http.response.status_code"))).isEqualTo(200L);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(span.getInstrumentationScopeInfo().getName()).isEqualTo(Tracing.SCOPE_NAME);
        provider.shutdown();
    }

    @Test
    void aRequestWithoutATraceHeaderStartsARootSpan() throws Exception {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        var app = Axiom.create();
        app.use(Tracing.create(OpenTelemetrySdk.builder().setTracerProvider(provider).build()));
        app.get("/plain", ctx -> ctx.noContent());

        try (var client = TestClient.start(app)) {
            assertThat(client.get("/plain").status()).isEqualTo(204);
        }

        var span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getParentSpanContext().isValid()).isFalse();
        assertThat(span.getName()).isEqualTo("GET /plain");
        provider.shutdown();
    }

    @Test
    void decoratesAnErrorResponseWithTheSpansIdentity() throws Exception {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        var app = Axiom.create();
        app.use(Tracing.create(OpenTelemetrySdk.builder().setTracerProvider(provider).build()));
        app.get("/bad", ctx -> { throw new BadRequestException("rejected"); });

        String header;
        try (var client = TestClient.start(app)) {
            var response = client.get("/bad");
            assertThat(response.status()).isEqualTo(400);
            header = response.headers().get(TraceContext.RESPONSE_HEADER);
            assertThat(header).isNotNull();
        }

        var span = exporter.getFinishedSpanItems().get(0);
        assertThat(header).isEqualTo("00-" + span.getTraceId() + "-" + span.getSpanId() + "-01");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.longKey("http.response.status_code"))).isEqualTo(400L);
        assertThat(span.getEvents()).extracting(event -> event.getName()).contains("exception");
        provider.shutdown();
    }

    @Test
    void recordsAnUnmappedFailureAsTheGenericServerError() throws Exception {
        var exporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        var app = Axiom.create();
        app.use(Tracing.create(OpenTelemetrySdk.builder().setTracerProvider(provider).build()));
        app.get("/boom", ctx -> { throw new IllegalStateException("boom"); });

        try (var client = TestClient.startMappingFailures(app)) {
            var response = client.get("/boom");
            assertThat(response.status()).isEqualTo(500);
            assertThat(response.headers()).doesNotContainKey(TraceContext.RESPONSE_HEADER);
        }

        var span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.longKey("http.response.status_code"))).isEqualTo(500L);
        assertThat(span.getEvents()).extracting(event -> event.getName()).contains("exception");
        provider.shutdown();
    }
}
