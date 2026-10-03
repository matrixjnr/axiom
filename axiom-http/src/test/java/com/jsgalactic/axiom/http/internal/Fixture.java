package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.lifecycle.Server;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** An application with listeners that are closed and joined after each test. */
final class Fixture implements AutoCloseable {
    final Application app = Axiom.create();
    final java.util.List<Server> servers = new java.util.ArrayList<>();
    private final java.util.List<Wire> wires = new java.util.ArrayList<>();
    Server listen() throws IOException { var server = app.listen(0); servers.add(server); return server; }
    /** Starts a listener and connects to it; for tests that register routes first. Closed with the fixture. */
    Wire connect() throws IOException {
        var wire = new Wire(listen());
        wires.add(wire);
        return wire;
    }
    @Override public void close() {
        for (var wire : wires) { try { wire.close(); } catch (IOException ignored) { /* Already gone. */ } }
        app.close();
        for (var server : servers) {
            server.close(); // Idempotent; stops listeners that were bound directly with test settings.
            server.termination().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }
}
