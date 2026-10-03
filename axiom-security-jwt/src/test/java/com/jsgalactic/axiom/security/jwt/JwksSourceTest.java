package com.jsgalactic.axiom.security.jwt;

import static com.jsgalactic.axiom.security.jwt.Jwks.bytes;
import static com.jsgalactic.axiom.security.jwt.Jwks.set;
import static com.jsgalactic.axiom.security.jwt.Tokens.claims;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The supplied URL and file sources against a real loopback server and real files. */
class JwksSourceTest {
    private final HttpServer server;
    private final CountDownLatch finished = new CountDownLatch(1);

    JwksSourceTest() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        finished.countDown();
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void handle(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        if (type != null) { exchange.getResponseHeaders().set("Content-Type", type); }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) { exchange.getResponseBody().write(body); }
        exchange.close();
    }

    private static String document() {
        return set(Jwks.rsa("k1", "RS256", (RSAPublicKey) Tokens.RSA.getPublic()));
    }

    @Test
    void fetchesAndVerifiesWithAKeySetFromAUrl() throws Exception {
        var requests = new AtomicInteger();
        handle("/jwks", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            respond(exchange, 200, "application/jwk-set+json; charset=utf-8", bytes(document()));
        });
        var jwt = JwtAuthenticatorTest.base().jwks(JwksSource.url(uri("/jwks"))).build();
        var token = Tokens.sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA");
        assertThat(jwt.verify(token).principal()).isEqualTo("ada");
        assertThat(jwt.verify(token).principal()).isEqualTo("ada");
        assertThat(requests).hasValue(1);
        handle("/json", exchange -> respond(exchange, 200, "application/json", bytes(document())));
        assertThat(JwksSource.url(uri("/json")).fetch(65_536)).isEqualTo(bytes(document()));
    }

    @Test
    void neverFollowsRedirects() throws Exception {
        var targetHits = new AtomicInteger();
        handle("/target", exchange -> {
            targetHits.incrementAndGet();
            respond(exchange, 200, "application/json", bytes(document()));
        });
        for (var status : new int[] {301, 302, 303, 307, 308}) {
            handle("/redirect" + status, exchange -> {
                exchange.getResponseHeaders().set("Location", uri("/target").toString());
                respond(exchange, status, null, new byte[0]);
            });
            assertThatIOException().isThrownBy(() -> JwksSource.url(uri("/redirect" + status)).fetch(65_536))
                    .withMessageContaining("status " + status).withMessageContaining("redirects are not followed");
        }
        assertThat(targetHits).hasValue(0);
        // A redirect leaves the authenticator without keys and the token rejected.
        var jwt = JwtAuthenticatorTest.base().jwks(JwksSource.url(uri("/redirect302"))).build();
        var token = Tokens.sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA");
        JwtAuthenticatorTest.rejects(jwt, token, "redirects are not followed");
        assertThat(targetHits).hasValue(0);
    }

    @Test
    void rejectsErrorStatusesWrongContentTypesAndOversizedBodies() throws Exception {
        handle("/500", exchange -> respond(exchange, 500, "application/json", bytes(document())));
        handle("/404", exchange -> respond(exchange, 404, "application/json", bytes("{}")));
        handle("/204", exchange -> respond(exchange, 204, "application/json", new byte[0]));
        handle("/html", exchange -> respond(exchange, 200, "text/html", bytes(document())));
        handle("/plain", exchange -> respond(exchange, 200, "text/plain", bytes(document())));
        handle("/none", exchange -> respond(exchange, 200, null, bytes(document())));
        handle("/big", exchange -> respond(exchange, 200, "application/json", bytes(document() + " ".repeat(5000))));
        handle("/chunked", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0); // chunked: the length is not declared
            var out = exchange.getResponseBody();
            for (int i = 0; i < 100; i++) { out.write(new byte[1000]); }
            exchange.close();
        });
        assertThatIOException().isThrownBy(() -> JwksSource.url(uri("/500")).fetch(65_536)).withMessageContaining("status 500");
        assertThatIOException().isThrownBy(() -> JwksSource.url(uri("/404")).fetch(65_536)).withMessageContaining("status 404");
        assertThatIOException().isThrownBy(() -> JwksSource.url(uri("/204")).fetch(65_536)).withMessageContaining("status 204");
        for (var path : new String[] {"/html", "/plain", "/none"}) {
            assertThatIOException().isThrownBy(() -> JwksSource.url(uri(path)).fetch(65_536)).withMessageContaining("content type");
        }
        assertThatIOException().isThrownBy(() -> JwksSource.url(uri("/big")).fetch(4096)).withMessageContaining("longer than 4096");
        assertThatIOException().isThrownBy(() -> JwksSource.url(uri("/chunked")).fetch(4096)).withMessageContaining("longer than 4096");
        assertThat(JwksSource.url(uri("/big")).fetch(65_536)).hasSizeGreaterThan(5000);
    }

    @Test
    void abandonsServersThatNeverAnswerOrNeverFinish() throws Exception {
        handle("/silent", exchange -> {
            try {
                finished.await(); // never answers until the test ends
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        handle("/slowbody", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(bytes("{\"keys\":"));
            exchange.getResponseBody().flush();
            try {
                finished.await(); // the rest of the body never arrives
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        var started = System.nanoTime();
        assertThatIOException().isThrownBy(() -> JwksSource.url(uri("/silent"), Duration.ofMillis(300)).fetch(65_536));
        // Either the client's own request timeout or the whole-exchange bound fires first; both end in an IOException.
        assertThatIOException().isThrownBy(() -> JwksSource.url(uri("/slowbody"), Duration.ofMillis(300)).fetch(65_536));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void anUnreachableServerIsAFetchFailure() throws Exception {
        var source = JwksSource.url(uri("/gone"), Duration.ofMillis(500));
        server.stop(0);
        assertThatIOException().isThrownBy(() -> source.fetch(65_536));
    }

    @Test
    void acceptsOnlySafeUrls() {
        assertThatIllegalArgumentException().isThrownBy(() -> JwksSource.url(URI.create("http://issuer.example.com/jwks")));
        assertThatIllegalArgumentException().isThrownBy(() -> JwksSource.url(URI.create("http://10.0.0.5/jwks")));
        assertThatIllegalArgumentException().isThrownBy(() -> JwksSource.url(URI.create("ftp://issuer.example.com/jwks")));
        assertThatIllegalArgumentException().isThrownBy(() -> JwksSource.url(URI.create("file:///etc/jwks.json")));
        assertThatIllegalArgumentException().isThrownBy(() -> JwksSource.url(URI.create("https://user:pw@issuer.example.com/jwks")));
        assertThatIllegalArgumentException().isThrownBy(() -> JwksSource.url(URI.create("https://issuer.example.com/jwks#frag")));
        assertThatIllegalArgumentException().isThrownBy(() -> JwksSource.url(URI.create("/jwks")));
        assertThatIllegalArgumentException().isThrownBy(() -> JwksSource.url(URI.create("https:///jwks")));
        assertThatIllegalArgumentException().isThrownBy(() -> JwksSource.url(URI.create("https://issuer.example.com/jwks"), Duration.ofMillis(99)));
        assertThatIllegalArgumentException().isThrownBy(() -> JwksSource.url(URI.create("https://issuer.example.com/jwks"), Duration.ofSeconds(61)));
        JwksSource.url(URI.create("https://issuer.example.com/.well-known/jwks.json"));
        JwksSource.url(URI.create("http://localhost:8080/jwks"));
        JwksSource.url(URI.create("http://[::1]:8080/jwks"));
    }

    @Test
    void readsKeySetsFromBoundedFiles(@TempDir Path directory) throws Exception {
        var file = directory.resolve("jwks.json");
        Files.writeString(file, document(), StandardCharsets.UTF_8);
        assertThat(JwksSource.file(file).fetch(65_536)).isEqualTo(bytes(document()));
        assertThatIOException().isThrownBy(() -> JwksSource.file(file).fetch(100)).withMessageContaining("longer than 100");
        assertThatIOException().isThrownBy(() -> JwksSource.file(directory.resolve("missing.json")).fetch(65_536));
        assertThatIOException().isThrownBy(() -> JwksSource.file(directory).fetch(65_536));
        // The file is read afresh, so a deployment tool can rotate keys by replacing it.
        var jwt = JwtAuthenticatorTest.base().jwks(JwksSource.file(file), JwksOptions.defaults().refreshInterval(Duration.ofSeconds(1))
                .minRefreshInterval(Duration.ofSeconds(1))).build();
        assertThat(jwt.refreshKeys()).isTrue();
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        assertThat(jwt.refreshKeys()).isFalse(); // the last good set stays
        var token = Tokens.sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA");
        assertThat(jwt.verify(token).principal()).isEqualTo("ada");
    }
}
