package com.jsgalactic.axiom.validation.jakarta;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import com.jsgalactic.axiom.validation.Validation;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Address;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Fragile;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Item;
import com.jsgalactic.axiom.validation.jakarta.Fixtures.Order;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Handlers validate hand-built records; no body codec is involved. */
class JakartaEndToEndTest {
    @Test
    void answersConstraintViolationsWith422ProblemJsonOfFieldsAndCodesOnly() throws Exception {
        try (var validation = JakartaValidation.create()) {
            var app = Axiom.create();
            app.post("/orders", ctx -> {
                var text = new String(ctx.request().body().bytes(), StandardCharsets.UTF_8);
                var order = new Order(text, text, new Address(text, text), List.of(new Item(text, 0)),
                        List.of(text), Map.of(text, text), BigDecimal.ZERO, true);
                Validation.require(validation, order);
                return ctx.noContent();
            });
            try (var client = TestClient.start(app)) {
                var response = client.post("/orders", "text/plain", " ${1+1}POISON ");
                assertThat(response.status()).isEqualTo(422);
                assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json");
                assertThat(text(response)).matches("\\{\"status\":422,\"code\":\"validation_failed\","
                        + "\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\",\"violations\":\\["
                        + "\\{\"field\":\"address.postcode\",\"code\":\"pattern\"},"
                        + "\\{\"field\":\"customer\",\"code\":\"size\"},"
                        + "\\{\"field\":\"discount\",\"code\":\"decimal_min\"},"
                        + "\\{\"field\":\"email\",\"code\":\"email\"},"
                        + "\\{\"field\":\"items\\[0].quantity\",\"code\":\"min\"}]}");
                assertThat(text(response)).doesNotContain("POISON", "${", "must", "message");
            }
        }
    }

    @Test
    void answersProviderFailuresWithAGeneric500() throws Exception {
        try (var validation = JakartaValidation.create()) {
            var app = Axiom.create();
            app.post("/fragile", ctx -> Validation.require(validation, new Fragile("POISON")).value());
            try (var client = TestClient.start(app)) {
                var response = client.post("/fragile", "text/plain", "x");
                assertThat(response.status()).isEqualTo(500);
                assertThat(text(response)).matches("\\{\"status\":500,\"code\":\"internal_server_error\","
                        + "\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\"}");
            }
        }
    }

    static String text(Response response) {
        var body = response.body();
        return body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(body);
    }
}
