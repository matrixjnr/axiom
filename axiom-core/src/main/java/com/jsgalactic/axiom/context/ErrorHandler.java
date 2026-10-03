package com.jsgalactic.axiom.context;

import com.jsgalactic.axiom.http.Response;

/**
 * Maps an exception thrown by a handler or middleware to a response.
 *
 * <pre>{@code
 * app.error(NoSuchElementException.class, (ctx, failure) -> { throw new NotFoundException(); });
 * app.error(QuotaExceeded.class, (ctx, failure) -> ctx.status(429).json(new Quota(failure.limit())));
 * }</pre>
 *
 * <p>Error handlers run after every middleware has unwound, on the request's thread and under its
 * deadline, with the request's context; the context's status is reset to 200 first. The response
 * is encoded by the installed codecs without an Accept check: an error is never answered 406,
 * because the client would then lose the error itself. Throwing an
 * {@link com.jsgalactic.axiom.error.AxiomException} answers with that exception's problem response; that
 * exception is not offered to error handlers again. Any other exception, or a {@code null}
 * result, is logged with the request ID and answered with the generic 500 problem response.
 * The original exception is logged once, with the request ID, unless it is an
 * {@code AxiomException} below 500 answered below 500; see
 * {@link com.jsgalactic.axiom.application.Application#failureLog}.
 * Error handlers never run for a cancelled or expired request, and are never offered
 * {@link InterruptedException} or {@link java.util.concurrent.CancellationException}.
 *
 * <p>Middleware have already unwound when an error handler runs, so headers they add after
 * {@code next.run()} are not on its response by themselves; each middleware that was entered
 * decorates the response through {@link Middleware#afterError}, as it does for problem responses.
 *
 * <p>Everything the returned response contains reaches the client: never copy exception
 * messages, class names or stack traces into it.
 *
 * <p><b>Lifecycle and thread safety.</b> Registered before startup; the application holds the
 * instance until it closes. One instance serves every request concurrently, so implementations
 * must be thread-safe. The context follows the handler's confinement rules.
 *
 * @param <E> exception type handled
 */
@FunctionalInterface
public interface ErrorHandler<E extends Exception> {
    /**
     * Maps one failure to a response.
     *
     * @param context the failed request's context, status reset to 200
     * @param failure the exception thrown by the handler or a middleware
     * @return the response to send; never null
     * @throws Exception an {@link com.jsgalactic.axiom.error.AxiomException} to answer with its problem
     *         response; anything else produces the generic 500
     */
    Response handle(Context context, E failure) throws Exception;
}
