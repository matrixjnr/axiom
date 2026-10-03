package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.context.BodyValidator;
import com.jsgalactic.axiom.error.Violation;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** ctx.validatedBody with the real Jackson codec, through TestClient. */
class ValidatedBodyJsonTest {
    /** Request body. */
    public record Order(String customer, int quantity) { }

    @Test
    void decodesWithJacksonThenAnswersViolationsWith422() throws Exception {
        var checks = new AtomicInteger();
        BodyValidator<Order> rules = order -> {
            checks.incrementAndGet();
            var violations = new ArrayList<Violation>();
            if (order.customer() == null || order.customer().isBlank()) { violations.add(new Violation("customer", "required")); }
            if (order.quantity() < 1 || order.quantity() > 10) { violations.add(new Violation("quantity", "range")); }
            return violations;
        };
        var app = Axiom.create();
        app.post("/orders", ctx -> ctx.status(201).json(ctx.validatedBody(Order.class, rules)));
        try (var client = TestClient.start(app)) {
            var created = client.post("/orders", "application/json", "{\"customer\":\"ada\",\"quantity\":2}");
            assertThat(created.status()).isEqualTo(201);
            assertThat(text(created)).isEqualTo("{\"customer\":\"ada\",\"quantity\":2}");

            var invalid = client.post("/orders", "application/json", "{\"customer\":\"  \",\"quantity\":99}");
            assertThat(invalid.status()).isEqualTo(422);
            assertThat(invalid.headers()).containsEntry("Content-Type", "application/problem+json");
            assertThat(text(invalid)).matches("\\{\"status\":422,\"code\":\"validation_failed\",\"requestId\":\"[^\"]+\","
                    + "\"violations\":\\[\\{\"field\":\"customer\",\"code\":\"required\"},"
                    + "\\{\"field\":\"quantity\",\"code\":\"range\"}]}");
            assertThat(checks).hasValue(2);

            var malformed = client.post("/orders", "application/json", "{\"customer\":");
            assertThat(malformed.status()).isEqualTo(400);
            var unknown = client.post("/orders", "application/json", "{\"customer\":\"a\",\"quantity\":1,\"POISON\":1}");
            assertThat(unknown.status()).isEqualTo(400);
            assertThat(text(unknown)).doesNotContain("POISON");
            assertThat(checks).hasValue(2);
        }
    }

    private static String text(Response response) {
        return new String((byte[]) response.body(), StandardCharsets.UTF_8);
    }
}
