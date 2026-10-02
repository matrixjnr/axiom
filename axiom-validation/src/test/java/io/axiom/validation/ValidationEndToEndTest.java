package io.axiom.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.error.Violation;
import io.axiom.http.Response;
import io.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs handlers through the real dispatcher and problem mapping, without a body codec. */
class ValidationEndToEndTest {
    record Signup(String name, String email) {
        /** Parses {@code name|email} from a raw text body; deliberately codec-free. */
        static Signup parse(byte[] body) {
            var text = new String(body, StandardCharsets.UTF_8);
            var bar = text.indexOf('|');
            return bar < 0 ? new Signup(text, null) : new Signup(text.substring(0, bar), text.substring(bar + 1));
        }
    }

    static final Validator<Signup> SIGNUP = signup -> {
        var violations = new ArrayList<Violation>();
        if (signup.name().isBlank() || signup.name().length() > 10) {
            violations.add(new Violation("name", "size"));
        }
        if (signup.email() == null) {
            violations.add(new Violation("email", "not_null"));
        }
        return violations;
    };

    static Application application(Validator<? super Signup> validator) {
        var app = Axiom.create();
        app.post("/signups", ctx -> {
            var signup = Validation.require(validator, Signup.parse(ctx.request().body().bytes()));
            return ctx.status(201).response("created " + signup.name());
        });
        return app;
    }

    @Test
    void answersValidRequestsNormally() throws Exception {
        try (var client = TestClient.start(application(SIGNUP))) {
            var response = client.post("/signups", "text/plain", "ada|ada@example.org");
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body()).isEqualTo("created ada");
        }
    }

    @Test
    void answersViolationsWith422ProblemJsonCarryingOnlyFieldsAndCodes() throws Exception {
        try (var client = TestClient.start(application(SIGNUP))) {
            var poison = "<script>POISON${1+1}</script>";
            var response = client.post("/signups", "text/plain", poison);
            assertThat(response.status()).isEqualTo(422);
            assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json");
            assertThat(text(response)).matches("\\{\"status\":422,\"code\":\"validation_failed\","
                    + "\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\",\"violations\":\\["
                    + "\\{\"field\":\"name\",\"code\":\"size\"},\\{\"field\":\"email\",\"code\":\"not_null\"}]}");
            assertThat(text(response)).doesNotContain("POISON", "script", "${", "2</");
        }
    }

    @Test
    void mapsRuleViolationsWithNestedPathsWithoutEchoingInput() throws Exception {
        record Batch(List<Signup> signups) {}
        var signup = Rules.of(Signup.class)
                .field("name", Signup::name, Rule.notBlank(), Rule.maxLength(10))
                .field("email", Signup::email, Rule.notNull(), Rule.email());
        var batch = Rules.of(Batch.class).eachNested("signups", Batch::signups, signup);
        var app = Axiom.create();
        app.post("/batches", ctx -> {
            var lines = new String(ctx.request().body().bytes(), StandardCharsets.UTF_8).split("\n");
            var signups = java.util.Arrays.stream(lines)
                    .map(line -> Signup.parse(line.getBytes(StandardCharsets.UTF_8))).toList();
            Validation.require(batch, new Batch(signups));
            return ctx.noContent();
        });
        try (var client = TestClient.start(app)) {
            assertThat(client.post("/batches", "text/plain", "ada|ada@example.org").status()).isEqualTo(204);
            var response = client.post("/batches", "text/plain",
                    "ada|ada@example.org\n|POISON@\nPOISON-name-too-long|x@example.org");
            assertThat(response.status()).isEqualTo(422);
            assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json");
            assertThat(fields(response)).containsExactly("signups[1].name", "signups[1].email", "signups[2].name");
            assertThat(text(response)).contains("{\"field\":\"signups[1].name\",\"code\":\"not_blank\"}",
                    "{\"field\":\"signups[1].email\",\"code\":\"email\"}",
                    "{\"field\":\"signups[2].name\",\"code\":\"size\"}");
            assertThat(text(response)).doesNotContain("POISON", "example.org", "message", "value");
        }
    }

    static String text(Response response) {
        var body = response.body();
        return body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(body);
    }

    static List<String> fields(Response response) {
        return java.util.regex.Pattern.compile("\"field\":\"([^\"]*)\"").matcher(text(response)).results()
                .map(match -> match.group(1)).toList();
    }
}
