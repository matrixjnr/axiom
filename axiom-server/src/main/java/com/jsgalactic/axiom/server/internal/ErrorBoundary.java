package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.AxiomException;
import com.jsgalactic.axiom.error.NotAcceptableException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.util.List;
import java.util.Objects;

/**
 * The outermost step of a route's chain, outside every middleware. It encodes the chain's response
 * (the Accept check, 406) and turns exceptions into responses with the error handlers of the
 * route's scopes, so that middleware can decorate those responses through
 * {@link Middleware#afterError}. An exception no handler maps and that is not an
 * {@link AxiomException} propagates.
 */
final class ErrorBoundary implements Handler {
    private final Handler chain;
    private final Middleware[] middleware;
    /** Error handlers of the route's scopes, innermost scope first. */
    private final List<ErrorHandlers> scopes;
    private final FailureLog log;
    /** Development errors: answers describe the exception. Off unless the application opted in. */
    private final boolean development;

    ErrorBoundary(Handler chain, Middleware[] middleware, List<ErrorHandlers> scopes, FailureLog log,
                  boolean development) {
        this.development = development;
        this.chain = chain;
        this.middleware = middleware;
        this.scopes = List.copyOf(scopes);
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public Object handle(Context context) throws Exception {
        var state = (DefaultContext) context;
        try {
            // Composed chains always return a Response; encoding runs once, after every middleware.
            var response = (Response) chain.handle(state);
            // A failure to encode happens after every middleware returned: all of them decorate it.
            state.entered(middleware.length);
            return encode(state.codecs(), state.request(), response, safe(state.request().method()));
        } catch (Exception failure) {
            return decorate(state, map(state, failure));
        }
    }

    /** Lets the middleware the failure passed through, innermost first, decorate the response. */
    private Response decorate(DefaultContext context, Response mapped) {
        var response = mapped;
        for (int i = context.entered() - 1; i >= 0; i--) {
            response = middleware[i].afterError(context, response);
            if (response == null) {
                throw new IllegalStateException("Middleware " + middleware[i].getClass().getName()
                        + " returned null from afterError; return the response, decorated or not");
            }
        }
        return response;
    }

    /**
     * Offers an exception to the registered error handlers, inner scopes first. Without one,
     * AxiomExceptions get their problem response and others are rethrown. An AxiomException thrown
     * by the error handler is answered with its problem response and not handled again; any other
     * failure of the error handler becomes the generic 500. Each failure is logged once.
     */
    private Response map(DefaultContext context, Exception failure) throws Exception {
        var requestId = context.execution().requestId();
        boolean cancelled = cancelled(context, failure);
        var handler = cancelled ? null : find(failure.getClass());
        if (handler == null) {
            if (!(failure instanceof AxiomException axiom)) {
                if (!development || cancelled) { throw failure; }
                // Development errors only: the listener would otherwise answer an opaque 500.
                log.failure("Request " + context.correlation() + " failed with an unexpected exception", failure);
                return described(genericFailure(requestId), failure);
            }
            if (axiom.status() >= 500) { logMapped(context, failure, axiom.status(), false); }
            return described(Problems.response(axiom, requestId), failure);
        }
        context.resetStatus();
        try {
            var response = handler.handle(context, failure);
            if (response == null) {
                throw new IllegalStateException("Error handler for " + failure.getClass().getName() + " returned null");
            }
            // Error responses are sent whatever the client accepts, like problem responses.
            var encoded = encode(context.codecs(), context.request(), response, false);
            logMapped(context, failure, encoded.status(), true);
            return encoded;
        } catch (AxiomException translated) {
            logMapped(context, failure, translated.status(), true);
            return Problems.response(translated, requestId);
        } catch (Exception broken) {
            if (broken instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            broken.addSuppressed(failure);
            log.defect("Request " + context.correlation() + " failed and its error handler failed too", broken);
            return described(genericFailure(requestId), broken);
        }
    }

    /** A framework-generated 500 closes the connection over HTTP, like the listener's own 500. */
    private static Response genericFailure(String requestId) {
        return Problems.response(500, requestId).withHeader("Connection", "close");
    }

    private Response described(Response problem, Throwable failure) {
        return development ? Problems.withDebug(problem, failure) : problem;
    }

    private com.jsgalactic.axiom.context.ErrorHandler<Exception> find(Class<?> type) {
        for (var scope : scopes) {
            var handler = scope.find(type);
            if (handler != null) { return handler; }
        }
        return null;
    }

    /**
     * Logs a failure, server-side only, once per request. A failure answered without an error
     * handler is logged only when it is a 5xx AxiomException; one an error handler mapped is also
     * logged when it was unexpected, unless it is an AxiomException below 500 answered below 500.
     */
    private void logMapped(DefaultContext context, Exception failure, int status, boolean handled) {
        boolean clientError = failure instanceof AxiomException axiom && axiom.status() < 500;
        if (status >= 500 || (handled && !clientError)) {
            var message = handled
                    ? "Request " + context.correlation() + " failed; its error handler answered " + status
                    : "Request " + context.correlation() + " failed with " + status + " "
                            + ((AxiomException) failure).code();
            log.failure(message, failure);
        }
    }

    /**
     * Whether the request was cancelled or ran out of time: its outcome is discarded, so no
     * application error handler runs. Interruption and cancellation are never offered at all.
     */
    private static boolean cancelled(DefaultContext context, Exception failure) {
        return failure instanceof InterruptedException || failure instanceof java.util.concurrent.CancellationException
                || Thread.currentThread().isInterrupted() || context.execution().isExpired();
    }

    /**
     * Whether the method is safe (RFC 9110 section 9.2.1), so that a 406 decided after the handler
     * ran cannot repeat a state change when the client retries. For other methods Accept is advisory.
     */
    private static boolean safe(String method) {
        return method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS") || method.equals("TRACE");
    }

    /**
     * Prepares a response whose Content-Type has an installed codec: checks the request's Accept
     * header (406 when nothing matches) and encodes values other than String and byte[].
     */
    static Response encode(Codecs codecs, Request request, Response response, boolean negotiate) {
        var mediaType = Codecs.mediaType(response.headers().get("Content-Type"));
        var codec = mediaType == null ? null : codecs.forMediaType(mediaType);
        if (codec == null) { return response; }
        if (negotiate && !Codecs.acceptable(request.header("Accept").orElse(null), mediaType)) {
            throw new NotAcceptableException();
        }
        var body = response.body();
        if (body == null || body instanceof String || body instanceof byte[] || response.isStreaming()) { return response; }
        var encoded = Response.of(response.status(), codec.encode(body));
        for (var header : response.headers().entrySet()) {
            encoded = encoded.withHeader(header.getKey(), header.getValue());
        }
        return encoded;
    }
}
