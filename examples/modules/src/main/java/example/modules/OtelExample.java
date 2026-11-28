package example.modules;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.otel.Tracing;
import io.opentelemetry.api.OpenTelemetry;

/**
 * Traces requests with the OpenTelemetry instance the application owns. A noop instance costs
 * almost nothing; pointing the same code at an SDK turns on real spans without touching a route.
 */
public final class OtelExample {
  private OtelExample() {}

  /**
   * Builds an application with tracing middleware around one route.
   *
   * @param openTelemetry the instance whose tracer creates the SERVER spans
   * @return a configured application, not yet started
   */
  public static Application create(OpenTelemetry openTelemetry) {
    var app = Axiom.create();
    app.use(Tracing.create(openTelemetry));
    app.get("/items/:id", ctx -> ctx.text("item " + ctx.path("id")));
    return app;
  }

  /**
   * Serves the traced route on http://127.0.0.1:8080/ until the process stops.
   *
   * @param args unused
   * @throws Exception if the listener cannot start
   */
  public static void main(String[] args) throws Exception {
    var app = create(OpenTelemetry.noop()).closeOnJvmShutdown();
    var server = app.listen(8080);
    System.out.println("Tracing with noop OpenTelemetry on http://127.0.0.1:8080/items/42");
    server.termination().toCompletableFuture().join();
  }
}
