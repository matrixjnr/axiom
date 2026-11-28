package example.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.test.TestClient;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;

class OtelExampleTest {
  @Test
  void recordsTheRequestAsAServerSpan() throws Exception {
    var exporter = InMemorySpanExporter.create();
    var provider =
        SdkTracerProvider.builder()
            .setSampler(Sampler.alwaysOn())
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();

    try {
      try (var client = TestClient.start(OtelExample.create(telemetry))) {
        assertThat(client.get("/items/42").status()).isEqualTo(200);
      }

      assertThat(exporter.getFinishedSpanItems())
          .singleElement()
          .satisfies(span -> {
            assertThat(span.getName()).isEqualTo("GET /items/:id");
            assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
            assertThat(span.getAttributes().get(AttributeKey.stringKey("http.route")))
                .isEqualTo("/items/:id");
            assertThat(span.getAttributes().get(AttributeKey.longKey("http.response.status_code")))
                .isEqualTo(200L);
          });
    } finally {
      provider.shutdown();
    }
  }
}
