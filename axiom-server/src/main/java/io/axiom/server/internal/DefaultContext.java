package io.axiom.server.internal;

import io.axiom.context.Context;
import io.axiom.http.Request;
import io.axiom.http.Response;

final class DefaultContext implements Context {
    private final Request request;
    private int status = 200;
    private boolean explicitStatus;

    DefaultContext(Request request) { this.request = request; }

    @Override
    public Request request() { return request; }

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
