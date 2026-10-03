package com.jsgalactic.axiom.integration;

import com.jsgalactic.axiom.Axiom;
import java.io.IOException;

/**
 * Child process of {@link ShutdownHookTest}: serves one slow route and opts in to closing on JVM
 * shutdown. It reports over stdout and releases its handler when the parent writes a line to stdin,
 * so the parent can order SIGTERM and the handler's completion without sleeping.
 */
public final class ShutdownHookMain {
    private ShutdownHookMain() { }

    /**
     * Starts the listener.
     * @param args unused
     * @throws Exception if the listener cannot start
     */
    public static void main(String[] args) throws Exception {
        var app = Axiom.create().closeOnJvmShutdown();
        app.get("/slow", ctx -> {
            System.out.println("ENTERED");
            System.out.flush();
            try { System.in.read(); } catch (IOException ignored) { /* The parent is gone. */ }
            return "finished";
        });
        var server = app.listen(0);
        System.out.println("LISTENING " + server.localAddress().getPort());
        System.out.flush();
        // Keeps the JVM alive until the hook has drained and closed the listener.
        server.termination().toCompletableFuture().join();
    }
}
