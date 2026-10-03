package com.jsgalactic.axiom.context;

import com.jsgalactic.axiom.http.Response;

/**
 * Code that runs around handlers: logging, timing, authentication, response headers.
 *
 * <pre>{@code
 * app.use((ctx, next) -> next.run().withHeader("X-Content-Type-Options", "nosniff"));
 * }</pre>
 *
 * <p>{@link Next#run()} runs the rest of the chain (further middleware, then the handler) and
 * returns its response; exceptions from the rest of the chain are thrown from it. A middleware
 * that returns without calling it short-circuits: the handler does not run and the returned
 * response is used.
 *
 * <p><b>Error responses are not decorated.</b> When the handler or an inner middleware throws,
 * the exception passes through every middleware and is mapped to a response only afterwards, so
 * headers a middleware adds after {@code next.run()} are <em>missing</em> on problem responses
 * and error handler responses. Security-header middleware must not rely on running for error
 * responses: also register a global {@link ErrorHandler} (for {@code Exception}, and for
 * {@code AxiomException} if those responses need the headers too, which replaces their built-in
 * problem body) that adds the headers, or catch the exception in the middleware.
 *
 * <p><b>Lifecycle and ownership.</b> Middleware are registered before startup with
 * {@code use} or as route-level arguments, and the application holds the instance until it
 * closes. Startup composes the chain of every route once; no chain is built per request.
 *
 * <p><b>Thread safety.</b> One instance serves every request, concurrently. Implementations must
 * be thread-safe and keep per-request state in local variables. A middleware runs on the
 * request's thread inside the admitted task, after admission and under the request deadline;
 * a timeout or cancellation interrupts it like a handler. The context and {@code next} follow
 * the handler's confinement rules: use them only on that thread and only until this method
 * returns. Exceptions it throws are handled exactly like handler exceptions.
 */
@FunctionalInterface
public interface Middleware {
    /**
     * Handles one request, usually by calling {@code next.run()}.
     *
     * @param context the request-scoped context shared with the handler; status settings made
     *        here apply to the handler's result mapping
     * @param next the rest of the chain
     * @return the response to use; never null
     * @throws Exception to fail the request as a handler exception would
     */
    Response handle(Context context, Next next) throws Exception;

    /**
     * The rest of a middleware chain for one request. Valid only during the middleware call that
     * received it, on the request's thread.
     */
    interface Next {
        /**
         * Runs the remaining middleware and the handler. The handler's result is returned as a
         * {@link Response} mapped as for handlers (text, bytes, {@code null} as 204, the context
         * status); values for a codec, such as {@code ctx.json(value)}, are still unencoded:
         * encoding and the Accept check run once after the whole chain.
         *
         * @return the response of the rest of the chain
         * @throws Exception whatever the rest of the chain throws
         * @throws IllegalStateException if called a second time, after the middleware returned, or
         *         from a thread other than the request's
         */
        Response run() throws Exception;
    }
}
