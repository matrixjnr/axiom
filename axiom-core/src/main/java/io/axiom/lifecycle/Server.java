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

    /** Initiates shutdown, closes connections and interrupts handlers; idempotent and nonblocking. */
    @Override void close();
}
