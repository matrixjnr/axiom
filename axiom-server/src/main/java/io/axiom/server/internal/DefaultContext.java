package io.axiom.server.internal;

import io.axiom.context.Context;
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
    public Context status(int status) {
        Response.validateStatus(status);
        this.status = status;
        explicitStatus = true;
        return this;
    }

    @Override
    public Response response(Object body) {
        return Response.of(body == null && !explicitStatus ? 204 : status, body);
    }
}
