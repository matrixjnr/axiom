package io.axiom.server.internal;

import io.axiom.context.Context;
import io.axiom.error.DecodeException;
import io.axiom.error.UnsupportedMediaTypeException;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.routing.Route;
import java.util.Map;

final class DefaultContext implements Context {
    private final Request request;
    private final ExecutionContext execution;
    private final CompiledRouter.Match match;
    private int status = 200;
    private boolean explicitStatus;

    DefaultContext(Request request, CompiledRouter.Match match, ExecutionContext execution) {
        this.request = request;
        this.execution = execution;
        this.match = match;
    }

    @Override
    public Route route() { return match.route(); }

    @Override
    public String path(String name) { return match.parameter(name); }

    @Override
    public Map<String, String> pathParameters() { return match.parameters(); }

    @Override
    public Request request() { return request; }

    @Override public ExecutionContext execution() { return execution; }

    @Override
    public <T> T body(Class<T> type) {
        java.util.Objects.requireNonNull(type, "type");
        var body = request.body();
        if (body.isEmpty()) { throw new DecodeException("empty_body"); }
        if (body.mediaType().isEmpty()) { throw new UnsupportedMediaTypeException("missing_content_type"); }
        throw new UnsupportedMediaTypeException();
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
            var route = match.route();
            throw new IllegalStateException("Route " + route.method() + " " + route.path() + " set status "
                    + status + ", which cannot carry a body, but produced a " + body.getClass().getName()
                    + " body; return null or a Response without a body");
        }
        return Response.of(body == null && !explicitStatus ? 204 : status, body);
    }
}
