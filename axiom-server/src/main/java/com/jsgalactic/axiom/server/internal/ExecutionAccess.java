package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;

/**
 * Internal bridge from an application to the execution budget it owns. Transports and the test
 * client use it to join the application's admission counters instead of creating private ones.
 * Not application API; the method is available only on applications created by this runtime.
 */
public interface ExecutionAccess {
    /**
     * Returns the application-owned dispatcher, starting the application if it is still being
     * configured. All listeners and test clients of the application share this instance.
     *
     * @return the shared execution budget and its workers
     * @throws IllegalStateException if the application is closed
     */
    RequestDispatcher execution();

    /**
     * Obtains the bridge of an application created by this runtime.
     * @param application configured, running or closed application
     * @return its execution bridge
     * @throws IllegalArgumentException if the application was not created by this runtime
     */
    static ExecutionAccess of(Application application) {
        if (application instanceof ExecutionAccess access) { return access; }
        throw new IllegalArgumentException("Application does not own an Axiom execution budget: " + application);
    }
}
