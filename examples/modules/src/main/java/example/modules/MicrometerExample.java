package example.modules;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.micrometer.MicrometerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Publishes the runtime's counters, gauges and timers into an application-owned registry. */
public final class MicrometerExample {
  private MicrometerExample() {}

  /**
   * Builds an application whose request measurements land in the supplied registry.
   *
   * @param registry the registry the application owns and publishes
   * @return a configured application, not yet started
   */
  public static Application create(MeterRegistry registry) {
    var app = Axiom.create();
    app.metrics(MicrometerMetrics.create(registry));
    app.get("/work", ctx -> ctx.text("done"));
    return app;
  }

  /**
   * Serves the measured route on http://127.0.0.1:8080/ until the process stops.
   *
   * @param args unused
   * @throws Exception if the listener cannot start
   */
  public static void main(String[] args) throws Exception {
    var registry = new SimpleMeterRegistry();
    var app = create(registry).closeOnJvmShutdown();
    var server = app.listen(8080);
    System.out.println("Publishing metrics to a SimpleMeterRegistry on http://127.0.0.1:8080/work");
    server.termination().toCompletableFuture().join();
  }
}
