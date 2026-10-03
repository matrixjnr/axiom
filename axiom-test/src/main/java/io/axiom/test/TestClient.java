package io.axiom.test;

import io.axiom.application.Application;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.Body;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.server.internal.Problems;
import io.axiom.server.internal.ResponseSerialization;
import io.axiom.server.internal.execution.RequestDispatcher;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/**
 * In-memory client that owns the supplied application's lifecycle.
 *
 * <p>Each call is admitted by a private dispatcher configured with the application's aggregate and
 * per-route admission policies and request timeout, and runs on a virtual thread, as in a network
 * listener. Admission outcomes map to the same statuses as the HTTP listener: 503 when capacity or
 * the queue is exhausted or the queue wait expires, and 504 when the request deadline expires.
 * Framework errors use the same {@code application/problem+json} bodies as the listener: request
 * bodies over the application's {@code maxRequestBody} get 413, unknown routes 404 (501 for an
 * unrecognized method), CONNECT 501, method
 * mismatches 405 with {@code Allow}, OPTIONS without an OPTIONS route 204 with {@code Allow}, and {@link io.axiom.error.AxiomException}s thrown by handlers
 * (including body decoding failures) their own status. Other exceptions thrown by a handler
 * propagate to the test instead of becoming a 500 response. After codec encoding, response bodies
 * must be {@code null}, {@code String} or {@code byte[]} and within the transport size limits;
 * anything else fails the call with {@link IllegalStateException}, where the listener would answer
 * 500; HEAD follows the same rules and returns the {@code Content-Length} a listener sends. Bodies are sent as raw bytes; this module installs no codec, so decoding uses whatever codec
 * the test's runtime classpath provides. Request targets may carry a query and are split and
 * validated by {@link Request#fromTarget(String, String)}, as the listener does; a target the
 * listener would answer with 400 throws {@link IllegalArgumentException} instead. Transport rules such as 414, 431, Expect handling,
 * pipelining and keep-alive are not modeled. Limits apply per client, as they do per listener.
 */
public final class TestClient implements AutoCloseable {
    private static final Object UNMATCHED = new Object();
    private final Application application;
    private final RequestDispatcher dispatcher;

    private TestClient(Application application) {
        this.application = Objects.requireNonNull(application, "application").start();
        this.dispatcher = new RequestDispatcher(application.admissionPolicy());
    }

    /**
     * Freezes registration and takes responsibility for closing the application.
     * @param application configured application
     * @return a client to use with try-with-resources
     */
    public static TestClient start(Application application) {
        return new TestClient(application);
    }

    /**
     * Executes a GET request for the supplied target.
     *
     * @param target absolute raw path, optionally followed by {@code ?} and a raw query
     * @return response
     * @throws IllegalArgumentException for a path or query the listener would answer with 400,
     *         validated as by {@link Request#fromTarget(String, String)}
     * @throws Exception if the handler fails
     */
    public Response get(String target) throws Exception {
        return execute(Request.fromTarget("GET", target));
    }

    /**
     * Executes a POST request with a UTF-8 text body, for example raw JSON.
     *
     * @param target absolute raw path, optionally with a query, as for {@link #get(String)}
     * @param contentType Content-Type header value, or null to send none
     * @param body text encoded as UTF-8
     * @return response
     * @throws Exception if the handler fails
     */
    public Response post(String target, String contentType, String body) throws Exception {
        return send("POST", target, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Executes a POST request with a binary body.
     *
     * @param target absolute raw path, optionally with a query, as for {@link #get(String)}
     * @param contentType Content-Type header value, or null to send none
     * @param body content; copied
     * @return response
     * @throws Exception if the handler fails
     */
    public Response post(String target, String contentType, byte[] body) throws Exception {
        return send("POST", target, contentType, body);
    }

    /**
     * Executes a PUT request with a UTF-8 text body.
     *
     * @param target absolute raw path, optionally with a query, as for {@link #get(String)}
     * @param contentType Content-Type header value, or null to send none
     * @param body text encoded as UTF-8
     * @return response
     * @throws Exception if the handler fails
     */
    public Response put(String target, String contentType, String body) throws Exception {
        return send("PUT", target, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Executes a PUT request with a binary body.
     *
     * @param target absolute raw path, optionally with a query, as for {@link #get(String)}
     * @param contentType Content-Type header value, or null to send none
     * @param body content; copied
     * @return response
     * @throws Exception if the handler fails
     */
    public Response put(String target, String contentType, byte[] body) throws Exception {
        return send("PUT", target, contentType, body);
    }

    /**
     * Executes a PATCH request with a UTF-8 text body.
     *
     * @param target absolute raw path, optionally with a query, as for {@link #get(String)}
     * @param contentType Content-Type header value, or null to send none
     * @param body text encoded as UTF-8
     * @return response
     * @throws Exception if the handler fails
     */
    public Response patch(String target, String contentType, String body) throws Exception {
        return send("PATCH", target, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Executes a PATCH request with a binary body.
     *
     * @param target absolute raw path, optionally with a query, as for {@link #get(String)}
     * @param contentType Content-Type header value, or null to send none
     * @param body content; copied
     * @return response
     * @throws Exception if the handler fails
     */
    public Response patch(String target, String contentType, byte[] body) throws Exception {
        return send("PATCH", target, contentType, body);
    }

    private Response send(String method, String target, String contentType, byte[] body) throws Exception {
        var headers = contentType == null ? Map.<String, String>of() : Map.of("Content-Type", contentType);
        return execute(Request.fromTarget(method, target).withHeaders(headers).withBody(Body.of(contentType, body)));
    }

    /**
     * Executes a request through admission and waits for its outcome.
     *
     * @param request request to execute
     * @return the response, or a 503 or 504 problem response for admission and deadline failures
     * @throws Exception if the handler fails
     * @throws IllegalStateException if the response body or headers cannot be sent by the transport
     */
    public Response execute(Request request) throws Exception {
        try {
            return submit(request).join();
        } catch (CompletionException failure) {
            var cause = failure.getCause();
            if (cause instanceof Exception exception) { throw exception; }
            if (cause instanceof Error error) { throw error; }
            throw failure;
        }
    }

    /**
     * Starts a request without waiting, so tests can hold capacity and observe queueing.
     * The future completes with a 503 or 504 response for admission and deadline failures, and
     * exceptionally for handler failures.
     *
     * @param request request to execute
     * @return the request outcome
     */
    public CompletableFuture<Response> submit(Request request) {
        Objects.requireNonNull(request, "request");
        var context = ExecutionContext.create(application.requestTimeout());
        var route = application.resolve(request);
        var policy = route.map(application::admissionPolicy).orElseGet(application::admissionPolicy);
        RequestDispatcher.Task<Response> task;
        try {
            task = dispatcher.submit(route.<Object>map(value -> value).orElse(UNMATCHED), policy, context, () -> {
                var response = application.handle(request, context);
                checkSerializable(response);
                return response;
            });
        } catch (RejectedExecutionException overloaded) {
            return CompletableFuture.completedFuture(Problems.response(503, context.requestId()));
        }
        return task.result().handle((response, thrown) -> {
            var failure = thrown;
            while (failure instanceof CompletionException && failure.getCause() != null) { failure = failure.getCause(); }
            if (failure == null) { return response; }
            if (failure instanceof RequestDispatcher.DeadlineExceededException) {
                return Problems.response(504, context.requestId());
            }
            if (failure instanceof RequestDispatcher.QueueTimeoutException
                    || failure instanceof RequestDispatcher.DispatchRejectedException) {
                return Problems.response(503, context.requestId());
            }
            throw new CompletionException(failure);
        }).toCompletableFuture();
    }

    /** Applies the listener's serialization rules; where it would answer 500, the call fails. */
    private static void checkSerializable(Response response) {
        var rejection = ResponseSerialization.rejection(response);
        if (rejection != null) { throw new IllegalStateException(rejection + "; the listener would return 500"); }
    }

    /** Cancels outstanding requests and closes the application. */
    @Override
    public void close() {
        dispatcher.close();
        application.close();
    }
}
