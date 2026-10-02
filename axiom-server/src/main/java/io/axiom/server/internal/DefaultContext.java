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

    private final Codecs codecs;

    DefaultContext(Request request, CompiledRouter.Match match, ExecutionContext execution, Codecs codecs) {
        this.request = request;
        this.codecs = codecs;
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
        var mediaType = body.mediaType();
        if (mediaType.isEmpty()) { throw new UnsupportedMediaTypeException("missing_content_type"); }
        if (body.charset().filter(charset -> !charset.equals("utf-8")).isPresent()) {
            throw new UnsupportedMediaTypeException("unsupported_charset");
        }
        var codec = codecs.forMediaType(mediaType.get());
        if (codec == null) { throw new UnsupportedMediaTypeException(); }
        var value = codec.decode(body.bytes(), type);
        if (value == null) { throw new DecodeException("null_body"); }
        return value;
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
