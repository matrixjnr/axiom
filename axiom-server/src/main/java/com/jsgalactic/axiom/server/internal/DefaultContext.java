package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.error.DecodeException;
import com.jsgalactic.axiom.error.UnsupportedMediaTypeException;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.observability.TraceContext;
import com.jsgalactic.axiom.routing.Route;
import java.util.Map;

/**
 * The context of one request. For a request no route serves ({@code match} is null), global
 * middleware run with this context around the router's own answer.
 */
final class DefaultContext implements Context {
    private final Request request;
    private final ExecutionContext execution;
    private final CompiledRouter.Match match;
    private final Response frameworkAnswer;
    private int status = 200;
    private boolean explicitStatus;
    private SecurityIdentity identity;
    private java.util.Optional<TraceContext> traceContext;

    private final Codecs codecs;

    DefaultContext(Request request, CompiledRouter.Match match, ExecutionContext execution, Codecs codecs,
                   Response frameworkAnswer) {
        this.request = request;
        this.codecs = codecs;
        this.execution = execution;
        this.match = match;
        this.frameworkAnswer = frameworkAnswer;
    }

    /** The router's answer (404, 405, automatic OPTIONS, 501) for a request no route serves. */
    Response frameworkAnswer() { return frameworkAnswer; }

    @Override
    public Route route() {
        if (match == null) {
            throw new IllegalStateException("No route serves this request; the router answers it itself."
                    + " Use matchedRoute() in global middleware");
        }
        return match.route();
    }

    @Override
    public java.util.Optional<Route> matchedRoute() {
        return match == null ? java.util.Optional.empty() : java.util.Optional.of(match.route());
    }

    @Override
    public String path(String name) {
        if (match == null) {
            throw new IllegalArgumentException("Unknown path parameter: " + java.util.Objects.requireNonNull(name, "name"));
        }
        return match.parameter(name);
    }

    @Override
    public Map<String, String> pathParameters() { return match == null ? Map.of() : match.parameters(); }

    @Override
    public Request request() { return request; }

    @Override public ExecutionContext execution() { return execution; }

    /** Parsed on first use; the context is thread-confined, so no synchronization is needed. */
    @Override
    public java.util.Optional<TraceContext> traceContext() {
        if (traceContext == null) { traceContext = Context.super.traceContext(); }
        return traceContext;
    }

    @Override
    public java.util.Optional<SecurityIdentity> identity() { return java.util.Optional.ofNullable(identity); }

    @Override
    public Context identity(SecurityIdentity identity) {
        java.util.Objects.requireNonNull(identity, "identity");
        if (this.identity != null) {
            throw new IllegalStateException("This request already has a security identity; it can be set only once");
        }
        this.identity = identity;
        return this;
    }

    @Override
    public <T> T body(Class<T> type) {
        java.util.Objects.requireNonNull(type, "type");
        var body = request.body();
        if (body.isEmpty()) { throw new DecodeException("empty_body"); }
        var mediaType = body.mediaType();
        if (mediaType.isEmpty()) { throw new UnsupportedMediaTypeException("missing_content_type"); }
        if (body.charset().filter(charset -> !charset.equals("utf-8")).isPresent()) {
            throw new UnsupportedMediaTypeException("unsupported_charset");
        }
        var codec = codecs.forMediaType(mediaType.get());
        if (codec == null) { throw new UnsupportedMediaTypeException(); }
        // A read-only view: the codec reads the request's bytes without another copy.
        var value = codec.decode(body.asReadOnlyBuffer(), type);
        if (value == null) { throw new DecodeException("null_body"); }
        return value;
    }

    /** Forgets the status the handler or middleware set, before an error handler runs. */
    void resetStatus() {
        status = 200;
        explicitStatus = false;
    }

    @Override
    public Context status(int status) {
        Response.validateStatus(status);
        this.status = status;
        explicitStatus = true;
        return this;
    }

    @Override
    public Response response(Object body) {
        if (body != null && (status == 204 || status == 205 || status == 304)) {
            var route = match == null ? "unmatched request " + request.method() : "Route " + match.route().method()
                    + " " + match.route().path();
            throw new IllegalStateException(route + " set status "
                    + status + ", which cannot carry a body, but produced a " + body.getClass().getName()
                    + " body; return null or a Response without a body");
        }
        return Response.of(body == null && !explicitStatus ? 204 : status, body);
    }
}
