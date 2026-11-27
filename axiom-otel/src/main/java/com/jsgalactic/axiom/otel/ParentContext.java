package com.jsgalactic.axiom.otel;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

/** Extracts a parent span context from the two W3C request headers with the OTel propagator. */
final class ParentContext {
  private static final TextMapGetter<String[]> GETTER =
      new TextMapGetter<>() {
        @Override
        public String get(String[] carrier, String key) {
          if ("traceparent".equals(key)) {
            return carrier[0];
          }
          return "tracestate".equals(key) ? carrier[1] : null;
        }
      };

  private ParentContext() {}

  static Context from(String traceparent, String tracestate) {
    return W3CTraceContextPropagator.getInstance()
        .extract(Context.root(), new String[] {traceparent, tracestate}, GETTER);
  }
}
