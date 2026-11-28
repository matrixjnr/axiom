package example.modules;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.graphql.GraphQl;
import graphql.GraphQL;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;

/** Serves one query from an application-owned graphql-java runtime on an Axiom route. */
public final class GraphQlExample {
  private static final String SDL =
      """
      type Query {
        greeting: String
      }
      """;

  private GraphQlExample() {}

  /**
   * Builds the runtime and registers POST and GET handlers at {@code /graphql}.
   *
   * @return a configured application, not yet started
   */
  public static Application create() {
    var wiring =
        RuntimeWiring.newRuntimeWiring()
            .type("Query", type -> type.dataFetcher("greeting", env -> "hello from graphql"))
            .build();
    var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL), wiring);
    var graphQL = GraphQL.newGraphQL(schema).build();

    var graphql = GraphQl.builder().schema(SDL).graphQL(graphQL).build();
    var app = Axiom.create();
    graphql.register(app, "/graphql");
    return app;
  }

  /**
   * Serves GraphQL on http://127.0.0.1:8080/graphql until the process stops.
   *
   * @param args unused
   * @throws Exception if the listener cannot start
   */
  public static void main(String[] args) throws Exception {
    var app = create().closeOnJvmShutdown();
    var server = app.listen(8080);
    System.out.println("GraphQL on http://127.0.0.1:8080/graphql");
    server.termination().toCompletableFuture().join();
  }
}
