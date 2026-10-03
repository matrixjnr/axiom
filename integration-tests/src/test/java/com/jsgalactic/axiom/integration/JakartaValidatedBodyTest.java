package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.http.Body;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.integration.Exchange.Reply;
import com.jsgalactic.axiom.lifecycle.Server;
import com.jsgalactic.axiom.test.TestClient;
import com.jsgalactic.axiom.validation.jakarta.JakartaValidation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code Context.validatedBody} with the real Jackson codec and the Jakarta adapter, through
 * {@code TestClient} and over a live listener. Responses may contain field paths and codes only.
 */
@Timeout(60)
class JakartaValidatedBodyTest {
    /** Request body with annotated components. */
    public record Line(@NotBlank String sku, @Min(1) int quantity) { }

    /** Request body. */
    public record NewOrder(@NotBlank @Size(max = 10) String customer, @Email String email,
                           List<@Valid @NotNull Line> lines) { }

    private static final String PROBLEM_HEAD = "\\{\"status\":422,\"code\":\"validation_failed\","
            + "\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\",\"violations\":";
    private static final List<String> LEAKS = List.of("POISON", "${", "not-an-email", "must", "message", "Exception",
            "jakarta", "hibernate", "NewOrder", "com.jsgalactic");

    private JakartaValidation validation;
    private Application app;
    private Server server;
    private final AtomicInteger handled = new AtomicInteger();

    /** Sends a JSON body to {@code POST /orders} and returns the answer. */
    private interface Sender { Reply post(String contentType, String body) throws Exception; }

    @BeforeEach
    void createApplication() {
        validation = JakartaValidation.create();
        app = Axiom.create();
        app.post("/orders", ctx -> {
            var order = ctx.validatedBody(NewOrder.class, validation);
            handled.incrementAndGet();
            return ctx.status(201).json(order);
        });
    }

    @AfterEach
    void close() throws Exception {
        if (server != null) {
            app.close();
            server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        validation.close();
    }

    @Test
    void validatesDecodedRecordsThroughTestClient() throws Exception {
        try (var client = TestClient.start(app)) {
            assertContract((contentType, body) -> {
                var request = new Request("POST", "/orders", Map.of("Content-Type", contentType),
                        Body.of(contentType, body.getBytes(StandardCharsets.UTF_8)));
                var response = client.execute(request);
                var content = response.body() instanceof byte[] bytes ? bytes : new byte[0];
                return Reply.of(response.status(), response.headers(), content);
            });
        }
    }

    @Test
    void validatesDecodedRecordsOverALiveListener() throws Exception {
        server = app.listen(0);
        assertContract((contentType, body) -> {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            try (var socket = new Socket()) {
                socket.connect(server.localAddress(), 5000);
                socket.setSoTimeout(10_000);
                var out = socket.getOutputStream();
                out.write(("POST /orders HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: "
                        + contentType + "\r\nContent-Length: " + bytes.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.write(bytes);
                out.flush();
                return MiddlewareListenerTest.read(socket.getInputStream(), false);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private void assertContract(Sender sender) throws Exception {
        var valid = sender.post("application/json",
                "{\"customer\":\"ada\",\"email\":\"ada@example.org\",\"lines\":[{\"sku\":\"pen\",\"quantity\":2}]}");
        assertThat(valid.status()).isEqualTo(201);
        assertThat(valid.body()).isEqualTo(
                "{\"customer\":\"ada\",\"email\":\"ada@example.org\",\"lines\":[{\"sku\":\"pen\",\"quantity\":2}]}");
        assertThat(handled).hasValue(1);

        // Field paths and codes only, sorted, from constraints on components and container elements.
        var invalid = sender.post("application/json", "{\"customer\":\"  \",\"email\":\"not-an-email\","
                + "\"lines\":[{\"sku\":\"pen\",\"quantity\":1},{\"sku\":\" \",\"quantity\":0}]}");
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.contentType()).isEqualTo("application/problem+json");
        assertThat(invalid.body()).matches(PROBLEM_HEAD
                + "\\[\\{\"field\":\"customer\",\"code\":\"not_blank\"},"
                + "\\{\"field\":\"email\",\"code\":\"email\"},"
                + "\\{\"field\":\"lines\\[1]\\.quantity\",\"code\":\"min\"},"
                + "\\{\"field\":\"lines\\[1]\\.sku\",\"code\":\"not_blank\"}]}");
        assertNoLeaks(invalid);

        // Values that look like expressions are never evaluated or echoed.
        var hostile = sender.post("application/json",
                "{\"customer\":\"${1+1}POISON-too-long\",\"email\":null,\"lines\":[null]}");
        assertThat(hostile.status()).isEqualTo(422);
        assertThat(hostile.body()).matches(PROBLEM_HEAD
                + "\\[\\{\"field\":\"customer\",\"code\":\"size\"},"
                + "\\{\"field\":\"lines\\[0]\",\"code\":\"not_null\"}]}");
        assertNoLeaks(hostile);
        assertThat(handled).hasValue(1);

        // Decoding failures come first: the validator never sees a value that did not decode.
        var unknown = sender.post("application/json", "{\"customer\":\"ada\",\"POISON\":1}");
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body()).contains("\"code\":\"unknown_field\"");
        var mismatch = sender.post("application/json", "{\"customer\":\"ada\",\"lines\":[{\"sku\":\"a\",\"quantity\":\"POISON\"}]}");
        assertThat(mismatch.status()).isEqualTo(400);
        assertThat(mismatch.body()).contains("\"code\":\"type_mismatch\"", "\"field\":\"lines[0].quantity\"");
        var malformed = sender.post("application/json", "{\"customer\":");
        assertThat(malformed.status()).isEqualTo(400);
        var nothing = sender.post("application/json", "null");
        assertThat(nothing.status()).isEqualTo(400);
        assertThat(nothing.body()).contains("\"code\":\"null_body\"");
        var wrongType = sender.post("text/plain", "{\"customer\":\"ada\"}");
        assertThat(wrongType.status()).isEqualTo(415);
        for (var reply : List.of(unknown, mismatch, malformed, nothing, wrongType)) { assertNoLeaks(reply); }
        assertThat(handled).hasValue(1);
    }

    private static void assertNoLeaks(Reply reply) {
        for (var leak : LEAKS) { assertThat(reply.body()).doesNotContain(leak); }
    }
}
