package io.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.context.BodyValidator;
import io.axiom.error.Violation;
import io.axiom.http.Body;
import io.axiom.http.Request;
import io.axiom.http.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** ctx.validatedBody through the in-memory runtime, with the test codec claiming application/json. */
class ValidatedBodyTest {
    record Item(String name, int quantity) { }

    private final AtomicInteger checks = new AtomicInteger();

    private final BodyValidator<Item> rules = item -> {
        checks.incrementAndGet();
        var violations = new ArrayList<Violation>();
        if (item.name().isBlank()) { violations.add(new Violation("name", "required")); }
        if (item.quantity() < 1) { violations.add(new Violation("quantity", "min")); }
        return violations;
    };

    private Application app(BodyValidator<? super Item> validator) {
        var app = Axiom.create();
        app.post("/items", ctx -> {
            var item = ctx.validatedBody(Item.class, validator);
            return ctx.status(201).text(item.name() + " x" + item.quantity());
        });
        return app.start();
    }

    private static Request post(String contentType, String body) {
        return new Request("POST", "/items").withBody(Body.of(contentType, body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(response.body());
    }

    @Test
    void returnsValidValues() throws Exception {
        try (var app = app(rules)) {
            var response = app.handle(post("application/json", "name=pen;quantity=2"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.body()).isEqualTo("pen x2");
        }
    }

    @Test
    void answersViolationsWith422ProblemsListingOnlyFieldsAndCodes() throws Exception {
        try (var app = app(rules)) {
            var response = app.handle(post("application/json", "name= ;quantity=0"));
            assertThat(response.status()).isEqualTo(422);
            assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json");
            assertThat(text(response)).matches("\\{\"status\":422,\"code\":\"validation_failed\",\"requestId\":\"[^\"]+\","
                    + "\"violations\":\\[\\{\"field\":\"name\",\"code\":\"required\"},"
                    + "\\{\"field\":\"quantity\",\"code\":\"min\"}]}");
        }
    }

    @Test
    void decodingFailuresComeFirstAndSkipTheValidator() throws Exception {
        try (var app = app(rules)) {
            assertThat(app.handle(post("text/plain", "name=pen;quantity=2")).status()).isEqualTo(415);
            assertThat(app.handle(post("application/json", "name=pen;quantity=many")).status()).isEqualTo(400);
            assertThat(app.handle(new Request("POST", "/items")).status()).isEqualTo(400);
            assertThat(checks).hasValue(0);
        }
    }

    @Test
    void acceptsValidatorsOfSupertypesAndCapsViolations() throws Exception {
        BodyValidator<Object> many = value -> IntStream.range(0, 150)
                .mapToObj(i -> new Violation("items[" + i + "]", "invalid")).toList();
        try (var app = app(many)) {
            var response = app.handle(post("application/json", "name=pen;quantity=2"));
            assertThat(response.status()).isEqualTo(422);
            assertThat(text(response)).contains("items[99]").doesNotContain("items[100]");
        }
    }

    @Test
    void keepsExactlyTheFirstHundredOfOneHundredAndOneViolations() throws Exception {
        BodyValidator<Item> many = value -> IntStream.range(0, 101)
                .mapToObj(i -> new Violation("items[" + i + "]", "invalid")).toList();
        try (var app = app(many)) {
            var text = text(app.handle(post("application/json", "name=pen;quantity=2")));
            assertThat(text.split("\\{\"field\"", -1)).hasSize(101);
            assertThat(text).contains("\"items[0]\"", "\"items[99]\"").doesNotContain("items[100]");
        }
    }

    @Test
    void rejectsBrokenValidators() throws Exception {
        try (var nullList = app(item -> null); var nullElement = app(item -> Collections.singletonList(null))) {
            assertThatIllegalStateException().isThrownBy(() -> nullList.handle(post("application/json", "name=a;quantity=1")));
            assertThatIllegalStateException().isThrownBy(() -> nullElement.handle(post("application/json", "name=a;quantity=1")));
        }
        try (var missing = app(null)) {
            assertThatThrownBy(() -> missing.handle(post("application/json", "name=a;quantity=1")))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
