package com.jsgalactic.axiom.graphql;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.BadRequestException;
import com.jsgalactic.axiom.error.GatewayTimeoutException;
import com.jsgalactic.axiom.error.InternalServerErrorException;
import com.jsgalactic.axiom.error.PayloadTooLargeException;
import com.jsgalactic.axiom.error.UnsupportedMediaTypeException;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.routing.Route;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.InvalidSyntaxError;
import graphql.execution.AbortExecutionException;
import graphql.execution.instrumentation.Instrumentation;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.validation.ValidationError;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.dataloader.DataLoaderRegistry;

/**
 * A GraphQL HTTP adapter: an Axiom {@link Handler} around a graphql-java runtime.
 *
 * <pre>{@code
 * var graphql = GraphQl.builder().schema(sdl).graphQL(applicationOwnedGraphQL).build();
 * app.post("/graphql", graphql.handler());                    // nothing is served by default
 * }</pre>
 *
 * <p>The schema is accepted as SDL and parsed when the handler is built, so a bad schema fails at
 * configuration time. The application owns its {@code graphql.GraphQL} instance (its runtime
 * wiring, data fetchers and execution strategies) and passes it to {@link Builder#graphQL(Object)}.
 * The parameter is deliberately typed as {@link Object}: no graphql-java type appears in an Axiom
 * signature, and the value is checked when the handler is built. When no instance is supplied the
 * adapter builds a read-only runtime from the SDL with graphql-java's default property fetchers.
 *
 * <p>The adapter adds its own instrumentation (query depth, query complexity, optional
 * introspection guard and the Axiom deadline) with {@code GraphQL.transform}, so the application's
 * instance is never mutated and its own instrumentation is retained. Each request gets a fresh
 * DataLoader registry (optionally filled by the application's own factory) and supports variables
 * and an operation name, and work is bounded by {@link GraphQlOptions}. Failures become {@code
 * application/problem+json} responses with fixed codes; GraphQL error messages, exception types and
 * stack traces never reach a client. Instances are immutable and thread-safe after construction; an
 * application-owned runtime must be thread-safe too.
 */
public final class GraphQl {
  private static final String JSON = "application/json";

  private final GraphQlOptions options;
  private final Handler handler;

  private GraphQl(GraphQlOptions options, Handler handler) {
    this.options = options;
    this.handler = handler;
  }

  /**
   * Starts a configuration. No route is registered until the caller registers {@link #handler()} or
   * calls {@link #register}.
   *
   * @return a builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the bounds of this handler.
   *
   * @return immutable options
   */
  public GraphQlOptions options() {
    return options;
  }

  /**
   * Returns the handler to register on an Axiom route.
   *
   * @return thread-safe handler
   */
  public Handler handler() {
    return handler;
  }

  /**
   * Registers POST, and GET when enabled, on one path. Registration is explicit: an application
   * that prefers to compose middleware itself can register {@link #handler()} directly.
   *
   * @param app application still configuring
   * @param path absolute route path
   * @param middleware route middleware, outermost first
   * @return the POST route
   */
  public Route register(Application app, String path, Middleware... middleware) {
    Objects.requireNonNull(app, "app");
    var post = app.post(path, handler, middleware);
    if (options.getQueries()) {
      app.get(path, handler, middleware);
    }
    return post;
  }

  /** Builds a {@link GraphQl}; not thread-safe, build once at startup. */
  public static final class Builder {
    private String schema;
    private Object graphQL;
    private GraphQlOptions options = GraphQlOptions.DEFAULT;
    private Supplier<Object> dataLoaders = DataLoaderRegistry::new;

    private Builder() {}

    /**
     * Sets the SDL schema. It is parsed and validated when {@link #build()} runs.
     *
     * @param sdl GraphQL schema definition language, non-blank
     * @return this builder
     */
    public Builder schema(String sdl) {
      this.schema = Objects.requireNonNull(sdl, "sdl");
      return this;
    }

    /**
     * Supplies the application-owned runtime. The value must be a {@code graphql.GraphQL}; anything
     * else fails the build. The instance is never modified: the adapter derives a transform that
     * adds its own instrumentation around the existing configuration.
     *
     * @param graphQL application-owned {@code graphql.GraphQL} instance
     * @return this builder
     */
    public Builder graphQL(Object graphQL) {
      this.graphQL = Objects.requireNonNull(graphQL, "graphQL");
      return this;
    }

    /**
     * Sets request and query bounds. Defaults to {@link GraphQlOptions#DEFAULT}.
     *
     * @param options immutable bounds
     * @return this builder
     */
    public Builder options(GraphQlOptions options) {
      this.options = Objects.requireNonNull(options, "options");
      return this;
    }

    /**
     * Sets a factory for the per-request DataLoader registry. It must return a {@code
     * org.dataloader.DataLoaderRegistry}; the parameter and result are typed as {@link Object} so
     * no graphql-java type appears in this API. Without a factory each request gets a fresh empty
     * registry.
     *
     * @param dataLoaders thread-safe factory called once per request
     * @return this builder
     */
    public Builder dataLoaders(Supplier<Object> dataLoaders) {
      this.dataLoaders = Objects.requireNonNull(dataLoaders, "dataLoaders");
      return this;
    }

    /**
     * Parses the schema, composes the instrumentation and builds the handler.
     *
     * @return an immutable adapter
     * @throws IllegalArgumentException if the SDL or the supplied runtime is invalid
     * @throws IllegalStateException if no schema was supplied
     */
    public GraphQl build() {
      if (schema == null || schema.isBlank()) {
        throw new IllegalStateException("A GraphQL SDL schema is required");
      }
      GraphQLSchema parsed;
      try {
        parsed =
            new SchemaGenerator()
                .makeExecutableSchema(
                    new SchemaParser().parse(schema), RuntimeWiring.newRuntimeWiring().build());
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Invalid GraphQL schema", e);
      }
      GraphQL supplied = null;
      if (graphQL != null) {
        if (!(graphQL instanceof GraphQL value)) {
          throw new IllegalArgumentException("graphQL must be a graphql.GraphQL instance");
        }
        supplied = value;
      }
      Instrumentation instrumentation = Limits.instrumentation(supplied, options);
      GraphQL runtime =
          supplied == null
              ? new GraphQL.Builder(parsed).instrumentation(instrumentation).build()
              : supplied.transform(builder -> builder.instrumentation(instrumentation));
      return new GraphQl(options, context -> execute(context, runtime, options, dataLoaders));
    }
  }

  private static Response execute(
      Context context, GraphQL runtime, GraphQlOptions options, Supplier<Object> dataLoaders) {
    String query;
    Map<String, Object> variables = Map.of();
    String operationName = null;
    if (context.method().equals("GET") || context.method().equals("HEAD")) {
      if (!options.getQueries()) {
        throw new BadRequestException("graphql_get_disabled");
      }
      query =
          context
              .query("query")
              .orElseThrow(() -> new BadRequestException("graphql_query_missing"));
      var raw = context.query("variables").orElse("");
      if (!raw.isEmpty()) {
        variables = object(parse(raw));
      }
      operationName = context.query("operationName").orElse(null);
    } else {
      var contentType =
          context
              .request()
              .body()
              .mediaType()
              .orElseThrow(() -> new UnsupportedMediaTypeException("missing_content_type"));
      if (!JSON.equals(contentType)) {
        throw new UnsupportedMediaTypeException();
      }
      byte[] bytes = context.request().body().bytes();
      if (bytes.length > options.maxBodyBytes()) {
        throw new PayloadTooLargeException("graphql_body_too_large");
      }
      var request = object(parse(decode(bytes)));
      if (!(request.get("query") instanceof String text) || text.isBlank()) {
        throw new BadRequestException("graphql_query_missing");
      }
      query = text;
      if (request.containsKey("variables")) {
        variables = object(request.get("variables"));
      }
      if (request.containsKey("operationName")) {
        if (!(request.get("operationName") instanceof String name)) {
          throw new BadRequestException("graphql_invalid_request");
        }
        operationName = name;
      }
      for (var key : request.keySet()) {
        if (!key.equals("query") && !key.equals("variables") && !key.equals("operationName")) {
          throw new BadRequestException("graphql_invalid_request");
        }
      }
    }
    if (query.length() > options.maxBodyBytes()) {
      throw new PayloadTooLargeException("graphql_body_too_large");
    }
    if (context.execution().isExpired()) {
      throw new GatewayTimeoutException("graphql_deadline_expired");
    }
    Object registry = dataLoaders.get();
    if (!(registry instanceof DataLoaderRegistry dataLoaderRegistry)) {
      throw new InternalServerErrorException("graphql_execution_failed");
    }
    var executionInput =
        ExecutionInput.newExecutionInput()
            .query(query)
            .variables(variables)
            .operationName(operationName)
            .dataLoaderRegistry(dataLoaderRegistry)
            .graphQLContext(Map.of(Limits.EXECUTION_KEY, context.execution()))
            .build();
    ExecutionResult result;
    try {
      result = runtime.execute(executionInput);
    } catch (CancellationException e) {
      executionInput.cancel();
      throw e;
    } catch (RuntimeException e) {
      throw new InternalServerErrorException("graphql_execution_failed");
    }
    failure(result.getErrors());
    if (!result.isDataPresent()) {
      throw new InternalServerErrorException("graphql_execution_failed");
    }
    var json = "{\"data\":" + Json.write(result.getData()) + "}";
    return context.encode(JSON, json).withHeader("Cache-Control", "no-store");
  }

  private static Object parse(String text) {
    try {
      return Json.parse(text);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("graphql_invalid_request");
    }
  }

  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new BadRequestException("graphql_invalid_request");
    }
    var result = new LinkedHashMap<String, Object>();
    for (var entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new BadRequestException("graphql_invalid_request");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static String decode(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new BadRequestException("graphql_invalid_request");
    }
  }

  private static void failure(List<GraphQLError> errors) {
    if (errors.isEmpty()) {
      return;
    }
    for (var error : errors) {
      if (error instanceof SafeError safe) {
        throw mapped(safe.code());
      }
      if (error instanceof AbortExecutionException abort) {
        for (var underlying : abort.getUnderlyingErrors()) {
          if (underlying instanceof SafeError safe) {
            throw mapped(safe.code());
          }
        }
      }
    }
    for (var error : errors) {
      if (error instanceof ValidationError
          || error instanceof InvalidSyntaxError
          || error instanceof AbortExecutionException) {
        throw new BadRequestException("graphql_invalid_query");
      }
    }
    throw new InternalServerErrorException("graphql_execution_failed");
  }

  private static RuntimeException mapped(String code) {
    return code.equals("graphql_deadline_expired")
        ? new GatewayTimeoutException(code)
        : new BadRequestException(code);
  }
}
