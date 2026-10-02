package io.axiom.lifecycle;

import io.axiom.execution.AdmissionSnapshot;
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
