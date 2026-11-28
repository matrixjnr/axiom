package example.modules;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.jpa.Jpa;
import example.modules.persistence.FakePersistence;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Gives each request its own EntityManager from an application-owned factory. The handler uses the
 * request-scoped accessor; the adapter commits on success and rolls back on an error response.
 */
public final class JpaExample {
  private JpaExample() {}

  /**
   * Builds an application with a per-request transaction boundary.
   *
   * @param factory the application's factory, never closed by the adapter
   * @return a configured application, not yet started
   */
  public static Application create(EntityManagerFactory factory) {
    var jpa = Jpa.builder().entityManagerFactory(factory).build();
    var managers = jpa.entityManagers();
    var app = Axiom.create();
    app.use(jpa.transaction());
    app.get(
        "/items",
        ctx -> {
          EntityManager manager = managers.get(ctx);
          return ctx.text("items open: " + manager.isOpen());
        });
    app.get("/conflict", ctx -> ctx.status(409).text("conflict"));
    return app;
  }

  /**
   * Serves the JPA routes on http://127.0.0.1:8080/ until the process stops.
   *
   * @param args unused
   * @throws Exception if the listener cannot start
   */
  public static void main(String[] args) throws Exception {
    var persistence = new FakePersistence();
    var app = create(persistence.factory()).closeOnJvmShutdown();
    var server = app.listen(8080);
    System.out.println("JPA boundaries on http://127.0.0.1:8080/items");
    server.termination().toCompletableFuture().join();
  }
}
