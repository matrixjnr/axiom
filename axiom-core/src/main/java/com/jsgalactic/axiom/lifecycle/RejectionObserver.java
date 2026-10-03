package com.jsgalactic.axiom.lifecycle;

/**
 * Observes the error responses an HTTP listener generates itself, for metrics and logging. Install
 * it with {@link ListenerOptions.Builder#rejectionObserver(RejectionObserver)}.
 *
 * <p>It is called once for each response the listener builds without running the application's
 * handlers or after they failed in a way the application did not map: 400, 408, 413, 414, 417,
 * 431, 501 (CONNECT, Upgrade and unsupported transfer codings), 503 (capacity, queue wait,
 * pipeline bounds, draining), 504, 505 and the listener's own 500 (an exception nothing mapped, an
 * unsendable response). It is not called for responses the application produces, including
 * problem responses for {@code AxiomException}s and the router's 404 and 405, nor for connections
 * the listener closes without a response (over the connection limit, idle, reset). Middleware
 * never see these requests, so this is the place to count them.
 *
 * <p>The observer can only watch: it cannot change the response or let a rejected request
 * through, and an exception it throws is logged and ignored. It runs on a listener thread just
 * before the response is written and must be fast, non-blocking and thread-safe. The arguments
 * carry only framework values (never request content): the response status, its problem
 * {@code code} and the request ID that the response carries in {@code X-Request-ID}.
 */
@FunctionalInterface
public interface RejectionObserver {
    /**
     * Called for one rejection.
     *
     * @param status the response status
     * @param code the response's problem code, for example {@code content_too_large}
     * @param requestId the request ID of the response, for correlating logs
     */
    void rejected(int status, String code, String requestId);
}
