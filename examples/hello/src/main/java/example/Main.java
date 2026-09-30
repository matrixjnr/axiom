package example;

import io.axiom.Axiom;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Minimal HTTP server, with a finite smoke mode for automated validation. */
public final class Main {
    private Main() {}

    /**
     * Starts a loopback HTTP listener.
     * @param args optional --smoke to verify a request on an ephemeral port and exit
     * @throws Exception if startup or smoke validation fails
     */
    public static void main(String[] args) throws Exception {
        boolean smoke = args.length == 1 && args[0].equals("--smoke");
        if (args.length > 0 && !smoke) { throw new IllegalArgumentException("Usage: [--smoke]"); }
        var app = Axiom.create();
        try {
            app.get("/", ctx -> "Hello, world!");
            var server = app.listen(smoke ? 0 : 8080);
            var shutdown = new Thread(() -> {
                app.close();
                server.termination().toCompletableFuture().join();
            }, "hello-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                var uri = URI.create("http://127.0.0.1:" + server.localAddress().getPort() + "/");
                System.out.println("Listening on " + uri);
                if (smoke) {
                    try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(5)).build()) {
                        var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                                .build(), HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() != 200 || !response.body().equals("Hello, world!")) {
                            throw new IllegalStateException("Unexpected response: " + response);
                        }
                        System.out.println(response.body());
                    }
                    app.close();
                    server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
                } else { server.termination().toCompletableFuture().join(); }
            } finally {
                app.close();
                server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
                Runtime.getRuntime().removeShutdownHook(shutdown);
            }
        } finally { app.close(); }
    }
}
