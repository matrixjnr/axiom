package com.jsgalactic.axiom.lifecycle;

import com.jsgalactic.axiom.execution.AdmissionSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

/** An application-owned listener. Closing a listener leaves its application running. */
public interface Server extends AutoCloseable {
    /**
     * Returns the bound address.
     * @return actual address, including the allocated port
     */
    InetSocketAddress localAddress();

    /**
     * Reports listener availability.
     * @return whether the listener is accepting connections
     */
    boolean isOpen();

    /**
     * Observes admission without running callbacks on transport threads.
     * @return a consistent snapshot for this listener, also available after shutdown
     */
    AdmissionSnapshot admission();

    /**
     * Observes shutdown without granting ownership of its completion.
     * @return completion after all listener resources and handlers have stopped
     */
    CompletionStage<Void> termination();

    /**
     * Re-reads the TLS key material and swaps it in atomically for connections accepted from now
     * on; connections that are open keep the material they negotiated with. Call it after a
     * certificate was renewed. If the new material is invalid, expired or does not match, the
     * exception is thrown and the previous material stays in use.
     *
     * @throws TlsConfigurationException if the new material cannot be used
     * @throws IOException if reloading failed for another reason
     * @throws IllegalStateException if this listener does not serve TLS
     */
    default void reloadTls() throws IOException {
        throw new IllegalStateException("This listener does not serve TLS");
    }

    /**
     * Starts a graceful drain and returns without waiting; idempotent and nonblocking, including
     * from a handler. The listener stops accepting connections at once and closes idle
     * connections and connections still receiving a request. Requests queued for execution
     * capacity are answered 503 without invoking their handlers. Running handlers may finish
     * within a fixed grace period (five seconds); their responses are sent with
     * {@code Connection: close}. After the grace period, remaining connections are force-closed
     * and their handlers interrupted. Await {@link #termination()} to join the shutdown.
     */
    @Override void close();
}
