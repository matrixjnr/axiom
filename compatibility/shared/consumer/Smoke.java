package consumer;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Body;
import com.jsgalactic.axiom.http.Request;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Starts an application from published artifacts and checks a plain and a JSON response. */
public final class Smoke {
    /** JSON request body. */
    public record Greeting(String name) { }

    /** JSON response body. */
    public record Reply(String message) { }

    private Smoke() {}

    /**
     * Runs the smoke test.
     * @param args unused
     * @throws Exception if the application misbehaves
     */
    public static void main(String[] args) throws Exception {
        checkRequestContract();
        var app = Axiom.create();
        try {
            app.get("/", ctx -> "Hello, world!");
            app.post("/greet", ctx -> ctx.json(new Reply("Hello, " + ctx.body(Greeting.class).name())));
            var server = app.listen(0);
            var base = "http://127.0.0.1:" + server.localAddress().getPort();
            try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5)).build()) {
                var hello = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                        .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
                expect(hello.statusCode() == 200 && hello.body().equals("Hello, world!"), "hello: " + hello.body());
                var greet = client.send(HttpRequest.newBuilder(URI.create(base + "/greet"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Axiom\"}"))
                        .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
                expect(greet.statusCode() == 200 && greet.body().equals("{\"message\":\"Hello, Axiom\"}"),
                    "json: " + greet.statusCode() + " " + greet.body());
            }
            app.close();
            server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
            System.out.println("SMOKE OK");
        } finally { app.close(); }
    }

    private static void checkRequestContract() {
        var body = Body.empty();
        var headers = Map.of("Accept", "text/plain");
        var peer = new InetSocketAddress("127.0.0.1", 1234);
        var request = new Request("GET", "/", "q=1", headers, body, peer, true);
        expect(!Request.class.isRecord() && java.lang.reflect.Modifier.isFinal(Request.class.getModifiers()), "Request shape");
        expect(request.method().equals("GET") && request.path().equals("/") && request.query().equals("q=1"), "request target");
        expect(request.headers().equals(headers) && request.body() == body, "request content");
        expect(request.remoteAddress().equals(peer) && request.tls() && request.isSecure(), "request transport");
        expect(new Request("GET", "/").equals(Request.get("/")), "two-argument constructor");
        expect(new Request("GET", "/", headers, body).equals(Request.get("/").withHeaders(headers)), "four-argument constructor");
        expect(new Request("GET", "/", "q=1", headers, body)
                .equals(Request.fromTarget("GET", "/?q=1").withHeaders(headers).withBody(body)), "five-argument constructor");
        expect(new Request("GET", "/", "q=1", headers, body, peer).withTls(true).equals(request), "six-argument constructor");
        var equal = request.withHeaders(Map.of("accept", "text/plain"));
        expect(request.equals(equal) && request.hashCode() == equal.hashCode(), "request equality and hash code");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException("Smoke check failed: " + message); }
    }
}
