package consumer;

import static com.jsgalactic.axiom.validation.Rule.email;
import static com.jsgalactic.axiom.validation.Rule.maxLength;
import static com.jsgalactic.axiom.validation.Rule.notBlank;
import static com.jsgalactic.axiom.validation.Rule.notNull;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.validation.Rules;
import com.jsgalactic.axiom.validation.Validation;
import com.jsgalactic.axiom.validation.Validator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Starts an application from published artifacts that uses axiom-validation and checks a valid
 * request (201) and an invalid one (422 with field and code only) over HTTP.
 */
public final class ValidationSmoke {
    /** JSON request body. */
    public record Signup(String name, String email) { }

    private static final Validator<Signup> SIGNUP = Rules.of(Signup.class)
            .field("name", Signup::name, notBlank(), maxLength(20))
            .field("email", Signup::email, notNull(), email());

    private ValidationSmoke() {}

    /**
     * Runs the smoke test.
     * @param args unused
     * @throws Exception if the application misbehaves
     */
    public static void main(String[] args) throws Exception {
        var app = Axiom.create();
        try {
            app.post("/signup", ctx -> {
                var signup = Validation.require(SIGNUP, ctx.body(Signup.class));
                return ctx.status(201).text("welcome " + signup.name());
            });
            var server = app.listen(0);
            var base = "http://127.0.0.1:" + server.localAddress().getPort();
            try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5)).build()) {
                var ok = post(client, base, "{\"name\":\"Ada\",\"email\":\"ada@example.com\"}");
                expect(ok.statusCode() == 201 && ok.body().equals("welcome Ada"), "valid: " + ok.statusCode() + " " + ok.body());
                var bad = post(client, base, "{\"name\":\" \",\"email\":\"not-an-email\"}");
                expect(bad.statusCode() == 422, "invalid status: " + bad.statusCode() + " " + bad.body());
                expect(bad.body().contains("\"code\":\"validation_failed\"")
                        && bad.body().contains("{\"field\":\"email\",\"code\":\"email\"}")
                        && bad.body().contains("{\"field\":\"name\",\"code\":\"not_blank\"}"),
                    "invalid body: " + bad.body());
                expect(!bad.body().contains("not-an-email"), "the response echoed the invalid value: " + bad.body());
            }
            app.close();
            server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
            System.out.println("VALIDATION SMOKE OK");
        } finally { app.close(); }
    }

    private static HttpResponse<String> post(HttpClient client, String base, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/signup"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void expect(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException("Validation smoke check failed: " + message); }
    }
}
