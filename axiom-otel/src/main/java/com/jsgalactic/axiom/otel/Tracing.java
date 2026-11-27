package com.jsgalactic.axiom.otel;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.AxiomException;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.observability.TraceContext;
import com.jsgalactic.axiom.observability.TraceState;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Objects;

/**
 * Creates one OpenTelemetry {@link SpanKind#SERVER SERVER} span per admitted request without adding
 * a dependency or an SPI to axiom-core. The span's parent comes from the caller's W3C {@code
 * traceparent} and {@code tracestate}, extracted by the OpenTelemetry API propagator; a request
 * without a valid header starts a root span.
 *
 * <p>Span names are {@code METHOD route-template} for a matched route and the method alone for a
 * router-generated answer. Attributes use the stable HTTP semantic conventions {@code
 * http.request.method}, {@code http.route} and {@code http.response.status_code}; raw paths, query
 * strings, headers and identities are never attributes. The tracer and meter of this module use the
 * instrumentation scope {@value #SCOPE_NAME}.
 *
 * <p>A successful response's status is recorded on the span before it ends. An exception is
 * recorded with {@link Span#recordException(Throwable)}, sets {@link StatusCode#ERROR}, and gets
 * the failing status of an {@link AxiomException} or 500 for any other exception; the span ends
 * before the exception leaves the middleware. Responses produced from exceptions do not flow back
 * through the chain, so {@link Middleware#afterError} adds a {@code traceresponse} header carrying
 * the span's identity to a mapped error response. A failure with no mapped error response has no
 * header to decorate.
 *
 * <p>The returned middleware is thread-safe: per-request state stays in local variables and one
 * confined thread-local slot. It makes the created span current while the chain runs, so
 * OpenTelemetry instrumentation on the same thread uses it as the parent.
 */
public final class Tracing {
  /** Instrumentation scope name of every tracer and meter this module creates. */
  public static final String SCOPE_NAME = "com.jsgalactic.axiom.otel";

  private static final AttributeKey<String> HTTP_REQUEST_METHOD =
      AttributeKey.stringKey("http.request.method");
  private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
  private static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE =
      AttributeKey.longKey("http.response.status_code");

  private Tracing() {}

  /**
   * Creates middleware using the tracer of {@link #SCOPE_NAME} from the supplied instance. The
   * caller owns that instance, its SDK, its sampler and its exporters; this module never installs
   * or configures them.
   *
   * @param openTelemetry tracer and propagator source, usually the application's SDK or {@link
   *     OpenTelemetry#noop()}
   * @return thread-safe tracing middleware
   */
  public static Middleware create(OpenTelemetry openTelemetry) {
    Objects.requireNonNull(openTelemetry, "openTelemetry");
    var version = Tracing.class.getPackage().getImplementationVersion();
    var tracer =
        version == null
            ? openTelemetry.getTracer(SCOPE_NAME)
            : openTelemetry.getTracer(SCOPE_NAME, version);
    return create(tracer);
  }

  /**
   * Creates middleware around a caller-supplied tracer. Use this overload when the caller chooses
   * the instrumentation scope; {@link #create(OpenTelemetry)} supplies the scope of this module.
   *
   * @param tracer tracer that creates the SERVER spans
   * @return thread-safe tracing middleware
   */
  public static Middleware create(Tracer tracer) {
    Objects.requireNonNull(tracer, "tracer");
    return new TracingMiddleware(tracer);
  }

  private static String spanName(Context context) {
    return context
        .matchedRoute()
        .map(route -> route.method() + " " + route.path())
        .orElseGet(context::method);
  }

  private static void finish(Span span, int status) {
    span.setAttribute(HTTP_RESPONSE_STATUS_CODE, status);
    span.end();
  }

  private static String traceresponse(SpanContext context) {
    return "00-"
        + context.getTraceId()
        + "-"
        + context.getSpanId()
        + "-"
        + (context.getTraceFlags().isSampled() ? "01" : "00");
  }

  private static final class TracingMiddleware implements Middleware {
    private final Tracer tracer;
    private final ThreadLocal<Pending> pending = new ThreadLocal<>();

    private TracingMiddleware(Tracer tracer) {
      this.tracer = tracer;
    }

    @Override
    public Response handle(Context context, Next next) throws Exception {
      pending.remove(); // A previous failure with no mapped response left no header to add.
      var builder =
          tracer
              .spanBuilder(spanName(context))
              .setSpanKind(SpanKind.SERVER)
              .setParent(
                  ParentContext.from(
                      context.header(TraceContext.HEADER).orElse(null),
                      context.header(TraceState.HEADER).orElse(null)))
              .setAttribute(HTTP_REQUEST_METHOD, context.method());
      context.matchedRoute().ifPresent(route -> builder.setAttribute(HTTP_ROUTE, route.path()));
      var span = builder.startSpan();
      Scope scope = span.makeCurrent();
      try {
        var response = next.run();
        finish(span, response.status());
        return response;
      } catch (Exception failure) {
        span.recordException(failure);
        span.setStatus(StatusCode.ERROR);
        span.setAttribute(
            HTTP_RESPONSE_STATUS_CODE,
            failure instanceof AxiomException axiom ? axiom.status() : 500);
        span.end();
        pending.set(new Pending(context, span.getSpanContext()));
        throw failure;
      } finally {
        scope.close();
      }
    }

    @Override
    public Response afterError(Context context, Response response) {
      var current = pending.get();
      if (current == null || current.context != context) {
        return response;
      }
      pending.remove();
      var span = current.span();
      return span.isValid()
          ? response.withHeader(TraceContext.RESPONSE_HEADER, traceresponse(span))
          : response;
    }
  }

  private record Pending(Context context, SpanContext span) {}
}
