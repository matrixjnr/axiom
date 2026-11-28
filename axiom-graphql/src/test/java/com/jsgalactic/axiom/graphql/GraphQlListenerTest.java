package com.jsgalactic.axiom.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.lifecycle.Server;
import graphql.GraphQL;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The same adapter over a live HTTP/1.1 listener, with no timing-dependent assertions. */
@Tag("integration")
class GraphQlListenerTest {
  private static final String SDL =
      """
      type Query { greeting: String }
      """;

  private static HttpResponse<String> send(
      HttpClient client, Server server, String method, String contentType, String body)
      throws Exception {
    var address = server.localAddress();
    var uri =
        URI.create(
            "http://"
                + address.getAddress().getHostAddress()
                + ":"
                + address.getPort()
                + "/graphql");
    var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    return client.send(
        builder
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void queryValidationAndSizeBoundsOverHttp() throws Exception {
    var wiring =
        RuntimeWiring.newRuntimeWiring()
            .type("Query", type -> type.dataFetcher("greeting", env -> "hello"))
            .build();
    var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL), wiring);
    var app = Axiom.create();
    GraphQl.builder()
        .schema(SDL)
        .graphQL(GraphQL.newGraphQL(schema).build())
        .options(new GraphQlOptions(80, 10, 100, false, false))
        .build()
        .register(app, "/graphql");
    try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      var server = app.listen(0);
      try {
        var ok = send(client, server, "POST", "application/json", "{\"query\":\"{ greeting }\"}");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(ok.body()).isEqualTo("{\"data\":{\"greeting\":\"hello\"}}");

        var invalid =
            send(client, server, "POST", "application/json", "{\"query\":\"{ missing }\"}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.headers().firstValue("Content-Type"))
            .contains("application/problem+json");
        assertThat(invalid.body())
            .contains("\"code\":\"graphql_invalid_query\"")
            .doesNotContain("ValidationError")
            .doesNotContain("graphql-java");

        var oversized =
            send(
                client,
                server,
                "POST",
                "application/json",
                "{\"query\":\"{ greeting }\"}" + " ".repeat(100));
        assertThat(oversized.statusCode()).isEqualTo(413);
        assertThat(oversized.body()).contains("\"code\":\"graphql_body_too_large\"");

        var refusedMethod = send(client, server, "GET", null, "");
        assertThat(refusedMethod.statusCode()).isEqualTo(405);
      } finally {
        server.close();
        server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
      }
    } finally {
      app.close();
    }
  }
}
