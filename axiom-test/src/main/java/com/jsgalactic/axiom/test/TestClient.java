package com.jsgalactic.axiom.test;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Body;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.http.StreamAbortedException;
import com.jsgalactic.axiom.server.internal.Problems;
import com.jsgalactic.axiom.server.internal.ResponseSerialization;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
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
 * unrecognized method), CONNECT 501, method mismatches 405 with {@code Allow}, OPTIONS without an
 * OPTIONS route 204 with {@code Allow}, and {@link com.jsgalactic.axiom.error.AxiomException}s thrown by
 * handlers (including body decoding failures) their own status. Other exceptions thrown by a handler
 * propagate to the test instead of becoming a 500 response. After codec encoding, response bodies
 * must be {@code null}, {@code String} or {@code byte[]} and within the transport size limits;
 * anything else fails the call with {@link IllegalStateException}, where the listener would answer
 * 500; streamed responses (see {@code Response.stream}) are collected into bytes by {@link #execute} and
 * {@link #submit}, with the listener's byte cap and deadline, and read incrementally with
 * {@link #stream}; HEAD follows the same rules and returns the {@code Content-Length} a listener sends. Bodies are sent as raw bytes; this module installs no codec, so decoding uses whatever codec
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
        this.dispatcher = new RequestDispatcher(application.admissionPolicy(), application.metrics());
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
     * exceptionally for handler failures. A streamed response completes the future once its body
     * has ended, with the whole body as bytes; use {@link #stream} to read it while it is written.
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
                return response.isStreaming() ? collect(response, context) : response;
            }, Response::status);
        } catch (RejectedExecutionException overloaded) {
            return CompletableFuture.completedFuture(Problems.response(503, context.requestId()));
        }
        return task.result().handle((response, thrown) -> {
            var failure = unwrap(thrown);
            if (failure == null) { return response; }
            var problem = problem(failure, context);
            if (problem != null) { return problem; }
            throw new CompletionException(failure);
        }).toCompletableFuture();
    }

    /**
     * Executes a request and returns as soon as the response head is known, so that a streamed
     * body (see {@code Response.stream} and {@code Response.sse}) can be read piece by piece with
     * {@link StreamedResponse#next()} while the handler is still writing it. A response that is
     * not streamed is returned as a single chunk. The request keeps its admission slot until the
     * body ends or the response is {@linkplain StreamedResponse#close() closed}.
     *
     * <p>Failures before the head is known behave as for {@link #execute}: admission and deadline
     * failures give a 503 or 504 problem response, and handler exceptions are thrown. Failures
     * after it surface through {@link StreamedResponse#completion()}.
     *
     * @param request request to execute
     * @return the response head and a reader for its body
     * @throws Exception if the handler fails before the head is known
     * @throws IllegalStateException if the response headers cannot be sent by the transport
     */
    public StreamedResponse stream(Request request) throws Exception {
        Objects.requireNonNull(request, "request");
        var context = ExecutionContext.create(application.requestTimeout());
        var route = application.resolve(request);
        var policy = route.map(application::admissionPolicy).orElseGet(application::admissionPolicy);
        var head = new CompletableFuture<StreamedResponse>();
        RequestDispatcher.Task<Response> task;
        try {
            task = dispatcher.submit(route.<Object>map(value -> value).orElse(UNMATCHED), policy, context, () -> {
                var response = application.handle(request, context);
                checkSerializable(response);
                if (!response.isStreaming()) {
                    head.complete(answered(response));
                    return response;
                }
                var stream = new StreamedResponse(response.status(), response.headers(), response.streamLimit(), context);
                head.complete(stream);
                response.streamBody().writeTo(stream.writer());
                // A body that swallowed the abort did not complete: the listener would have cut the connection.
                var reason = stream.aborted();
                if (reason != null) { throw new StreamAbortedException(reason); }
                return response.withoutBody();
            }, Response::status);
        } catch (RejectedExecutionException overloaded) {
            return answered(Problems.response(503, context.requestId()));
        }
        task.result().whenComplete((response, thrown) -> {
            var failure = unwrap(thrown);
            if (!head.isDone()) {
                // The head was never known: the outcome is an admission or deadline answer, or a failure.
                var problem = failure == null ? null : problem(failure, context);
                if (failure == null) { head.complete(answered(response)); }
                else if (problem != null) { head.complete(answered(problem)); }
                else { head.completeExceptionally(failure); }
            } else {
                // After the head, the reader learns the end and its cause; unread chunks stay readable.
                head.join().end(failure);
            }
        });
        StreamedResponse stream;
        try {
            stream = head.join();
        } catch (CompletionException failure) {
            var cause = unwrap(failure);
            if (cause instanceof Exception exception) { throw exception; }
            if (cause instanceof Error error) { throw error; }
            throw failure;
        }
        stream.attach(task::cancel);
        return stream;
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) { failure = failure.getCause(); }
        return failure;
    }

    /** The response a client sees for an admission or deadline failure, or null for any other failure. */
    private static Response problem(Throwable failure, ExecutionContext context) {
        if (failure instanceof RequestDispatcher.DeadlineExceededException) {
            return Problems.response(504, context.requestId());
        }
        if (failure instanceof RequestDispatcher.QueueTimeoutException
                || failure instanceof RequestDispatcher.DispatchRejectedException) {
            return Problems.response(503, context.requestId());
        }
        return null;
    }

    /** A response that is complete already, as a stream of one chunk that has ended. */
    private static StreamedResponse answered(Response response) {
        var body = ResponseSerialization.bodyBytes(response.body());
        var stream = StreamedResponse.whole(response.status(), response.headers(), body == null ? new byte[0] : body);
        stream.end(null);
        return stream;
    }

    /** Runs a stream body to its end in memory, with the listener's cap and deadline rules. */
    private static Response collect(Response response, ExecutionContext context) throws Exception {
        var collector = new StreamedResponse.Collector(response.streamLimit(), context);
        response.streamBody().writeTo(collector);
        // A body that swallowed the abort did not complete: the listener would have cut the connection.
        if (collector.aborted() != null) { throw new StreamAbortedException(collector.aborted()); }
        var collected = Response.of(response.status(), collector.toByteArray());
        for (var header : response.headers().entrySet()) {
            collected = collected.withHeader(header.getKey(), header.getValue());
        }
        return collected;
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
