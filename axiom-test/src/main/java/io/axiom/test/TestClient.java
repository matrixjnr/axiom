package io.axiom.test;

import io.axiom.application.Application;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.server.internal.execution.RequestDispatcher;
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
 * Exceptions thrown by a handler propagate to the test instead of becoming a 500 response.
 * Response bodies must be {@code null}, {@code String} or {@code byte[]} and within the transport
 * size limits; anything else fails the call with {@link IllegalStateException}, where the listener
 * would answer 500. No socket or serializer is involved, and connection-level behavior such as
 * pipelining and keep-alive is not modeled. Limits apply per client, as they do per listener.
 */
public final class TestClient implements AutoCloseable {
    private static final Object UNMATCHED = new Object();
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final int MAX_HEADERS = 8192;
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
     * Executes a GET request for the supplied path.
     *
     * @param path absolute raw path
     * @return response
     * @throws Exception if the handler fails
     */
    public Response get(String path) throws Exception {
        return execute(Request.get(path));
    }

    /**
     * Executes a request through admission and waits for its outcome.
     *
     * @param request request to execute
     * @return the response, or a 503 or 504 response for admission and deadline failures
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
            return CompletableFuture.completedFuture(error(503, "Service unavailable"));
        }
        return task.result().handle((response, thrown) -> {
            var failure = thrown;
            while (failure instanceof CompletionException && failure.getCause() != null) { failure = failure.getCause(); }
            if (failure == null) { return response; }
            if (failure instanceof RequestDispatcher.DeadlineExceededException) { return error(504, "Gateway timeout"); }
            if (failure instanceof RequestDispatcher.QueueTimeoutException
                    || failure instanceof RequestDispatcher.DispatchRejectedException) {
                return error(503, "Service unavailable");
            }
            throw new CompletionException(failure);
        }).toCompletableFuture();
    }

    private static Response error(int status, String message) { return Response.of(status, message); }

    /** Mirrors the listener's response preparation, which answers 500 for these cases. */
    private static void checkSerializable(Response response) {
        var body = response.body();
        if (body != null && !(body instanceof byte[]) && !(body instanceof String)) {
            throw new IllegalStateException("Response body of type " + body.getClass().getName()
                    + " cannot be serialized by the transport (only String and byte[]); the listener would return 500");
        }
        if (body instanceof String text && text.length() > MAX_RESPONSE
                || body instanceof byte[] bytes && bytes.length > MAX_RESPONSE) {
            throw new IllegalStateException("Response body exceeds " + MAX_RESPONSE + " bytes; the listener would return 500");
        }
        int size = 0;
        for (Map.Entry<String, String> header : response.headers().entrySet()) {
            size += header.getKey().length() + header.getValue().length() + 4;
            if (size > MAX_HEADERS || header.getValue().chars().anyMatch(c -> c > 255)) {
                throw new IllegalStateException("Response headers are too large or not Latin-1; the listener would return 500");
            }
        }
    }

    /** Cancels outstanding requests and closes the application. */
    @Override
    public void close() {
        dispatcher.close();
        application.close();
    }
}
