package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Request;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Typed query and path accessors through the application: conversion failures become safe 400s. */
class TypedParametersTest {
    @Test
    void convertsParametersAndAnswersFailuresWithAProblemThatDoesNotEchoInput() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/items/:id/:n", ctx -> ctx.pathUuid("id") + " " + ctx.pathLong("n") + " "
                    + ctx.queryInt("page").orElse(1) + " " + ctx.queryUuid("ref").map(UUID::toString).orElse("-"));
            app.start();
            var id = "123e4567-e89b-12d3-a456-426614174000";
            var ok = app.handle(Request.fromTarget("GET", "/items/" + id + "/9?page=3&ref=" + id));
            assertThat(ok.body()).isEqualTo(id + " 9 3 " + id);
            assertThat(app.handle(Request.fromTarget("GET", "/items/" + id + "/9")).body()).isEqualTo(id + " 9 1 -");

            var badQuery = app.handle(Request.fromTarget("GET", "/items/" + id + "/9?page=secret-token"));
            assertProblem(badQuery, "invalid_query_parameter", "secret-token");
            var emptyQuery = app.handle(Request.fromTarget("GET", "/items/" + id + "/9?page="));
            assertProblem(emptyQuery, "invalid_query_parameter", "page");
            var badPath = app.handle(Request.fromTarget("GET", "/items/" + id + "/12x"));
            assertProblem(badPath, "invalid_path_parameter", "12x");
            var badUuid = app.handle(Request.fromTarget("GET", "/items/not-a-uuid/9"));
            assertProblem(badUuid, "invalid_path_parameter", "not-a-uuid");
        }
    }

    private static void assertProblem(com.jsgalactic.axiom.http.Response response, String code, String input) {
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json");
        var body = new String((byte[]) response.body(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"status\":400", "\"code\":\"" + code + "\"").doesNotContain(input);
    }
}
