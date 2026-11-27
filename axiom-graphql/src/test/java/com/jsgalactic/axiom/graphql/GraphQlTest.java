package com.jsgalactic.axiom.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import graphql.GraphQL;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderRegistry;
import org.junit.jupiter.api.Test;

/**
 * Deterministic coverage of the GraphQL adapter through the real dispatcher and {@code TestClient}.
 */
class GraphQlTest {
  private static final String SDL =
      """
            type Query {
              greeting: String
              upper(id: ID!): String
              pair(first: ID!, second: ID!): [String]
              boom: String
              deep: Nested
            }
            type Nested { child: Nested, label: String }
            type Mutation { increment: Int }
            """;

  private static final String POISON = "POISON<script>java.lang.Runtime";

  private static GraphQL runtime(AtomicInteger counter) {
    var wiring =
        RuntimeWiring.newRuntimeWiring()
            .type(
                "Query",
                type ->
                    type.dataFetcher("greeting", env -> "hello")
                        .dataFetcher("upper", env -> ((String) env.getArgument("id")).toUpperCase())
                        .dataFetcher(
                            "pair",
                            env ->
                                List.of(
                                    ((String) env.getArgument("first")).toUpperCase(),
                                    ((String) env.getArgument("second")).toUpperCase()))
                        .dataFetcher(
                            "boom",
                            env -> {
                              throw new IllegalStateException(POISON);
                            })
                        .dataFetcher("deep", env -> Map.of("child", Map.of("label", "value"))))
            .type(
                "Mutation", type -> type.dataFetcher("increment", env -> counter.incrementAndGet()))
            .build();
    var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL), wiring);
    return GraphQL.newGraphQL(schema).build();
  }

  private static GraphQL runtime() {
    return runtime(new AtomicInteger());
  }

  private static GraphQL loaderRuntime() {
    var wiring =
        RuntimeWiring.newRuntimeWiring()
            .type(
                "Query",
                type ->
                    type.dataFetcher(
                        "pair",
                        env -> {
                          DataLoader<String, String> loader = env.getDataLoader("names");
                          var first = loader.load(env.getArgument("first"));
                          var second = loader.load(env.getArgument("second"));
                          return first.thenCombine(second, List::of);
                        }))
            .build();
    var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL), wiring);
    return GraphQL.newGraphQL(schema).build();
  }

  private static TestClient client(GraphQl graphql) {
    var app = Axiom.create();
    graphql.register(app, "/graphql");
    return TestClient.start(app);
  }

  private static String text(Response response) {
    return response.body() instanceof String value
        ? value
        : new String((byte[]) response.body(), StandardCharsets.UTF_8);
  }

  private static String body(TestClient client, String json) throws Exception {
    return text(client.post("/graphql", "application/json", json));
  }

  private static void problem(TestClient client, String json, int status, String code)
      throws Exception {
    var response = client.post("/graphql", "application/json", json);
    assertThat(response.status()).as(text(response)).isEqualTo(status);
    assertThat(response.headers().get("Content-Type")).isEqualTo("application/problem+json");
    var body = text(response);
    assertThat(body)
        .contains("\"code\":\"" + code + "\"")
        .doesNotContain(POISON)
        .doesNotContain("IllegalStateException")
        .doesNotContain("graphql-java");
  }

  @Test
  void simpleQueryUsesTheApplicationOwnedRuntime() throws Exception {
    try (var client = client(GraphQl.builder().schema(SDL).graphQL(runtime()).build())) {
      assertThat(body(client, "{\"query\":\"{ greeting }\"}"))
          .isEqualTo("{\"data\":{\"greeting\":\"hello\"}}");
    }
  }

  @Test
  void variablesAndOperationNameArePassedThrough() throws Exception {
    try (var client = client(GraphQl.builder().schema(SDL).graphQL(runtime()).build())) {
      var json =
          "{\"query\":\"query Who($id: ID!) { upper(id: $id) }\","
              + "\"operationName\":\"Who\",\"variables\":{\"id\":\"abc\"}}";
      assertThat(body(client, json)).isEqualTo("{\"data\":{\"upper\":\"ABC\"}}");
    }
  }

  @Test
  void mutationsRunAndKeepStateInTheApplicationRuntime() throws Exception {
    var counter = new AtomicInteger();
    try (var client = client(GraphQl.builder().schema(SDL).graphQL(runtime(counter)).build())) {
      assertThat(body(client, "{\"query\":\"mutation { increment }\"}"))
          .isEqualTo("{\"data\":{\"increment\":1}}");
      assertThat(body(client, "{\"query\":\"mutation { increment }\"}"))
          .isEqualTo("{\"data\":{\"increment\":2}}");
    }
    assertThat(counter.get()).isEqualTo(2);
  }

  @Test
  void validationAndResolverErrorsAreOpaqueProblems() throws Exception {
    try (var client = client(GraphQl.builder().schema(SDL).graphQL(runtime()).build())) {
      problem(client, "{\"query\":\"{ missing }\"}", 400, "graphql_invalid_query");
      problem(client, "{\"query\":\"{ greeting\"}", 400, "graphql_invalid_query");
      problem(client, "{\"query\":\"{ boom }\"}", 500, "graphql_execution_failed");
    }
  }

  @Test
  void malformedEnvelopesAreOpaqueProblems() throws Exception {
    try (var client = client(GraphQl.builder().schema(SDL).graphQL(runtime()).build())) {
      problem(client, "{\"query\":\"{ greeting }\",\"extra\":1}", 400, "graphql_invalid_request");
      problem(client, "{\"query\":123}", 400, "graphql_query_missing");
      problem(client, "not json", 400, "graphql_invalid_request");
      var noType = client.post("/graphql", null, "{\"query\":\"{ greeting }\"");
      assertThat(noType.status()).isEqualTo(415);
    }
  }

  @Test
  void requestSizeIsBounded() throws Exception {
    var options = new GraphQlOptions(80, 10, 100, false, false);
    try (var client =
        client(GraphQl.builder().schema(SDL).options(options).graphQL(runtime()).build())) {
      var oversized =
          client.post(
              "/graphql", "application/json", "{\"query\":\"{ greeting }\"}" + " ".repeat(100));
      assertThat(oversized.status()).as(text(oversized)).isEqualTo(413);
      assertThat(text(oversized)).contains("\"code\":\"graphql_body_too_large\"");
      assertThat(body(client, "{\"query\":\"{ greeting }\"}"))
          .isEqualTo("{\"data\":{\"greeting\":\"hello\"}}");
    }
  }

  @Test
  void depthComplexityAndIntrospectionAreBounded() throws Exception {
    try (var client = client(GraphQl.builder().schema(SDL).graphQL(runtime()).build())) {
      problem(
          client,
          "{\"query\":\"{ __schema { queryType { name } } }\"}",
          400,
          "graphql_introspection_disabled");
    }
    try (var client =
        client(
            GraphQl.builder()
                .schema(SDL)
                .options(new GraphQlOptions(4096, 10, 100, true, false))
                .graphQL(runtime())
                .build())) {
      assertThat(body(client, "{\"query\":\"{ __schema { queryType { name } } }\"}"))
          .contains("\"__schema\"");
    }
    try (var client =
        client(
            GraphQl.builder()
                .schema(SDL)
                .options(new GraphQlOptions(4096, 2, 100, false, false))
                .graphQL(runtime())
                .build())) {
      problem(
          client,
          "{\"query\":\"{ deep { child { child { label } } } }\"}",
          400,
          "graphql_query_too_deep");
    }
    try (var client =
        client(
            GraphQl.builder()
                .schema(SDL)
                .options(new GraphQlOptions(4096, 10, 1, false, false))
                .graphQL(runtime())
                .build())) {
      problem(
          client,
          "{\"query\":\"{ greeting upper(id: \\\"a\\\") }\"}",
          400,
          "graphql_query_too_complex");
    }
  }

  @Test
  void dataLoadersArePerRequestAndBatchWithinARequest() throws Exception {
    var batches = new AtomicInteger();
    var created = new AtomicInteger();
    Supplier<Object> factory =
        () -> {
          created.incrementAndGet();
          var registry = new DataLoaderRegistry();
          registry.register(
              "names",
              DataLoaderFactory.<String, String>newDataLoader(
                  keys -> {
                    batches.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        keys.stream().map(String::toUpperCase).toList());
                  }));
          return registry;
        };
    try (var client =
        client(
            GraphQl.builder().schema(SDL).dataLoaders(factory).graphQL(loaderRuntime()).build())) {
      assertThat(body(client, "{\"query\":\"{ pair(first: \\\"a\\\", second: \\\"b\\\") }\"}"))
          .isEqualTo("{\"data\":{\"pair\":[\"A\",\"B\"]}}");
      assertThat(batches.get()).isEqualTo(1);
      assertThat(body(client, "{\"query\":\"{ pair(first: \\\"c\\\", second: \\\"d\\\") }\"}"))
          .isEqualTo("{\"data\":{\"pair\":[\"C\",\"D\"]}}");
      assertThat(batches.get()).isEqualTo(2);
      assertThat(created.get()).isEqualTo(2);
    }
  }

  @Test
  void getQueriesAndHeadOptionsFollowRouterRules() throws Exception {
    var app = Axiom.create();
    GraphQl.builder()
        .schema(SDL)
        .options(new GraphQlOptions(4096, 10, 100, false, true))
        .graphQL(runtime())
        .build()
        .register(app, "/graphql");
    try (var client = TestClient.start(app)) {
      assertThat(text(client.get("/graphql?query=%7Bgreeting%7D")))
          .isEqualTo("{\"data\":{\"greeting\":\"hello\"}}");
      assertThat(client.execute(Request.fromTarget("HEAD", "/graphql")).body()).isNull();
      var allowed = client.execute(Request.fromTarget("OPTIONS", "/graphql"));
      assertThat(allowed.status()).isEqualTo(204);
      assertThat(allowed.headers().get("Allow")).contains("GET", "HEAD", "POST", "OPTIONS");
    }
    var postOnly = Axiom.create();
    GraphQl.builder().schema(SDL).graphQL(runtime()).build().register(postOnly, "/graphql");
    try (var client = TestClient.start(postOnly)) {
      assertThat(client.get("/graphql?query=%7Bgreeting%7D").status()).isEqualTo(405);
    }
  }

  @Test
  void theAxiomExecutionContextReachesResolversAndDoesNotLeakBetweenRequests() throws Exception {
    var seen = new ArrayList<String>();
    var wiring =
        RuntimeWiring.newRuntimeWiring()
            .type(
                "Query",
                type ->
                    type.dataFetcher(
                        "greeting",
                        env -> {
                          var execution = env.getGraphQlContext().get(Limits.EXECUTION_KEY);
                          assertThat(execution).isInstanceOf(ExecutionContext.class);
                          var axiom = (ExecutionContext) execution;
                          seen.add(axiom.requestId());
                          assertThat(axiom.remainingTime().isNegative()).isFalse();
                          return "hello";
                        }))
            .build();
    var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL), wiring);
    try (var client =
        client(GraphQl.builder().schema(SDL).graphQL(GraphQL.newGraphQL(schema).build()).build())) {
      assertThat(body(client, "{\"query\":\"{ greeting }\"}"))
          .isEqualTo("{\"data\":{\"greeting\":\"hello\"}}");
      assertThat(body(client, "{\"query\":\"{ greeting }\"}"))
          .isEqualTo("{\"data\":{\"greeting\":\"hello\"}}");
    }
    assertThat(seen).hasSize(2);
    assertThat(seen.get(0)).isNotBlank().isNotEqualTo(seen.get(1));
  }

  @Test
  void configurationFailuresAreEager() {
    assertThatIllegalStateException().isThrownBy(() -> GraphQl.builder().build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GraphQl.builder().schema("not a schema").build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GraphQl.builder().schema(SDL).graphQL("not a runtime").build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new GraphQlOptions(0, 10, 100, false, false));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new GraphQlOptions(4096, 0, 100, false, false));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new GraphQlOptions(4096, 10, 0, false, false));
  }

  @Test
  void nothingIsServedUntilRegistered() throws Exception {
    var app = Axiom.create();
    GraphQl.builder().schema(SDL).graphQL(runtime()).build();
    try (var client = TestClient.start(app)) {
      assertThat(
              client.post("/graphql", "application/json", "{\"query\":\"{ greeting }\"}").status())
          .isEqualTo(404);
    }
  }

  @Test
  void defaultRuntimeServesReadOnlyData() throws Exception {
    try (var client = client(GraphQl.builder().schema("type Query { greeting: String }").build())) {
      assertThat(body(client, "{\"query\":\"{ greeting }\"}"))
          .isEqualTo("{\"data\":{\"greeting\":null}}");
    }
  }
}
