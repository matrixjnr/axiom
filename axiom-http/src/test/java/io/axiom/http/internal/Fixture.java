package io.axiom.http.internal;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.lifecycle.Server;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** An application with listeners that are closed and joined after each test. */
final class Fixture implements AutoCloseable {
    final Application app = Axiom.create();
    final java.util.List<Server> servers = new java.util.ArrayList<>();
    Server listen() throws IOException { var server = app.listen(0); servers.add(server); return server; }
    @Override public void close() {
        app.close();
        for (var server : servers) { server.termination().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join(); }
    }
}
