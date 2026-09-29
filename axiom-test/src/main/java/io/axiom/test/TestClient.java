package io.axiom.test;

import io.axiom.application.Application;
import io.axiom.http.Request;
import io.axiom.http.Response;
import java.util.Objects;

/**
 * In-memory client that owns the supplied application's lifecycle.
 * Calls run through the application's actual dispatcher on the calling thread.
 * Exceptions propagate to the test; no socket, executor, or serializer is involved.
 */
public final class TestClient implements AutoCloseable {
    private final Application application;

    private TestClient(Application application) {
        this.application = Objects.requireNonNull(application, "application").start();
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
     * Executes a request through the application dispatcher.
     *
     * @param request request to execute
     * @return response
     * @throws Exception if the handler fails
     */
    public Response execute(Request request) throws Exception {
        return application.handle(request);
    }

    /** Closes the application. Already accepted requests may finish. */
    @Override
    public void close() { application.close(); }
}
