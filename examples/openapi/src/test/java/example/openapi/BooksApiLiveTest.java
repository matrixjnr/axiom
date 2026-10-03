package example.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The documentation routes on a real socket, including the Swagger UI bundle that is streamed. */
@Tag("integration")
class BooksApiLiveTest {
    @Test void servesTheUiItsAssetsAndTheDocumentsOverHttp() throws Exception {
        var password = UUID.randomUUID().toString();
        var authorization = "Basic " + Base64.getEncoder().encodeToString(
                ("docs:" + password).getBytes(StandardCharsets.UTF_8));
        var app = BooksApi.create(DocsAccess.requirePassword(password));
        try (var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            var server = app.listen(0);
            var base = "http://127.0.0.1:" + server.localAddress().getPort();

            var anonymous = http.send(request(base + "/docs", null), HttpResponse.BodyHandlers.ofString());
            assertThat(anonymous.statusCode()).isEqualTo(401);
            assertThat(anonymous.headers().firstValue("WWW-Authenticate")).isPresent();

            var page = http.send(request(base + "/docs", authorization), HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("script-src 'self'");
            var bundle = Pattern.compile("src=\"(/docs/assets/[^\"]+/swagger-ui-bundle\\.js)\"").matcher(page.body());
            assertThat(bundle.find()).isTrue();

            var script = http.send(request(base + bundle.group(1), authorization), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(script.statusCode()).isEqualTo(200);
            assertThat(script.headers().firstValue("Content-Type")).contains("text/javascript; charset=utf-8");
            assertThat(script.body().length).isGreaterThan(1_048_576);

            var document = http.send(request(base + "/openapi.json", authorization), HttpResponse.BodyHandlers.ofString());
            assertThat(document.statusCode()).isEqualTo(200);
            assertThat(document.body()).contains("\"openapi\": \"3.1.0\"");
        } finally {
            app.close();
        }
    }

    private static HttpRequest request(String url, String authorization) {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder.build();
    }
}
