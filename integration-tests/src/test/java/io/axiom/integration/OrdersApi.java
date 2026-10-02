package io.axiom.integration;

import io.axiom.Axiom;
import io.axiom.application.Application;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A small JSON API whose bodies use records, java.time, enums, UUID, BigDecimal and Optional. */
final class OrdersApi {
    /** Large enough for a string just over the codec's 1 Mi character limit. */
    static final int MAX_BODY = 2 * 1024 * 1024;

    enum Status { NEW, PAID }

    record Line(String sku, int quantity, double weight) { }

    record Order(UUID id, String customer, List<Line> lines, Instant placedAt, LocalDate deliverOn,
                 Duration window, BigDecimal total, Status status, Optional<String> note,
                 Map<String, Object> attributes) { }

    static final Order SAMPLE = new Order(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "Ada",
            List.of(new Line("pen", 2, 0.25)), Instant.parse("2024-02-29T10:15:30Z"), LocalDate.of(2024, 3, 4),
            Duration.ofHours(2), new BigDecimal("12.50"), Status.PAID, Optional.of("ring twice"),
            Map.of("gift", true));

    /** The JSON form of {@link #SAMPLE}, in record component order. */
    static final String SAMPLE_JSON = "{\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"customer\":\"Ada\","
            + "\"lines\":[{\"sku\":\"pen\",\"quantity\":2,\"weight\":0.25}],\"placedAt\":\"2024-02-29T10:15:30Z\","
            + "\"deliverOn\":\"2024-03-04\",\"window\":\"PT2H\",\"total\":12.50,\"status\":\"PAID\","
            + "\"note\":\"ring twice\",\"attributes\":{\"gift\":true}}";

    private OrdersApi() { }

    static Application create() {
        var app = Axiom.create().maxRequestBody(MAX_BODY);
        // Echoes the decoded order, so a response proves what the codec produced.
        app.post("/orders", ctx -> ctx.status(201).json(ctx.body(Order.class)));
        app.get("/orders/sample", ctx -> ctx.json(SAMPLE));
        return app;
    }
}
