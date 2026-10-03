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
 * <p><b>Error responses.</b> When the handler or an inner middleware throws, the exception passes
 * through every middleware that is on the stack, so each can still observe or translate it, and
 * is mapped to a response only afterwards (problem response, error handler response, or 406).
 * That response does not come back through {@code next.run()}; instead each middleware that was
 * entered gets to decorate it through {@link #afterError}, innermost first. A security-header
 * middleware therefore implements both methods:
 *
 * <pre>{@code
 * Middleware headers = new Middleware() {
 *     public Response handle(Context ctx, Next next) throws Exception { return secure(next.run()); }
 *     public Response afterError(Context ctx, Response response) { return secure(response); }
 * };
 * }</pre>
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
     * Decorates a response that was produced from an exception: a problem response for an
     * {@link com.jsgalactic.axiom.error.AxiomException}, the response of an error handler, the
     * generic 500, or the 406 for an unacceptable response type. The default returns the response
     * unchanged.
     *
     * <p>It is called once per such response for each middleware that was entered when the
     * failure happened (a middleware an outer one short-circuited before was never entered),
     * innermost first, after error mapping and before the response leaves {@code app.handle}.
     * It is not called for responses that come back through {@link Next#run()}, including
     * responses a middleware or handler builds itself, nor for the router's 404, 405 and 501
     * answers (those flow through global middleware as ordinary responses), nor for
     * failures before routing (413, CONNECT) and listener errors. An exception that nothing
     * maps, and a request that was cancelled or timed out, never reach it.
     *
     * <p>Like {@link #handle}, it runs on the request's thread under the request deadline and
     * must be thread-safe. It must not throw: an exception from it is not mapped again. An
     * {@link com.jsgalactic.axiom.error.AxiomException} answers with its own undecorated problem
     * response and anything else fails the request as an unexpected exception.
     *
     * @param context the failed request's context
     * @param response the response produced from the exception, never null
     * @return the response to pass outwards; never null
     */
    default Response afterError(Context context, Response response) {
        return response;
    }

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
