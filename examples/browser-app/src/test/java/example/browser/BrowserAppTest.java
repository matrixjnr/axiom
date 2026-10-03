package example.browser;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.security.TrustedProxies;
import com.jsgalactic.axiom.test.TestClient;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BrowserAppTest {
    private final String code = UUID.randomUUID().toString();

    private TestClient client() {
        return TestClient.start(BrowserApp.create((user, supplied) -> user.equals("ada") && supplied.equals(code), TrustedProxies.none()));
    }

    private static Request request(String method, String target, String... headers) throws Exception {
        var map = new HashMap<String, String>();
        for (int i = 0; i < headers.length; i += 2) { map.put(headers[i], headers[i + 1]); }
        return Request.fromTarget(method, target).withHeaders(map)
                .withRemoteAddress(new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 4000));
    }

    private static String text(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(response.body());
    }

    private static String cookie(Response response) {
        var header = response.headers().get("Set-Cookie");
        return header.substring(0, header.indexOf(';'));
    }

    @Test
    void signsInWithCsrfTokenKeepsDataPerSessionAndSignsOut() throws Exception {
        try (var client = client()) {
            var page = client.execute(request("GET", "/csrf"));
            var token = text(page);
            var anonymous = cookie(page);
            // No token: rejected before the credentials are even looked at.
            assertThat(client.execute(request("POST", "/login?user=ada", "Cookie", anonymous, "X-Login-Code", code)).status()).isEqualTo(403);
            var wrong = client.execute(request("POST", "/login?user=ada", "Cookie", anonymous, "X-CSRF-Token", token, "X-Login-Code", "nope"));
            assertThat(wrong.status()).isEqualTo(401);
            var login = client.execute(request("POST", "/login?user=ada", "Cookie", anonymous, "X-CSRF-Token", token, "X-Login-Code", code));
            assertThat(login.status()).isEqualTo(204);
            var session = cookie(login);
            assertThat(session).isNotEqualTo(anonymous);
            assertThat(login.headers().get("Set-Cookie")).contains("Secure", "HttpOnly", "SameSite=Lax");
            assertThat(text(client.execute(request("GET", "/me", "Cookie", session)))).isEqualTo("ada");
            assertThat(client.execute(request("GET", "/me", "Cookie", anonymous)).status()).isEqualTo(401);

            var fresh = text(client.execute(request("GET", "/csrf", "Cookie", session)));
            assertThat(client.execute(request("POST", "/notes?text=hello", "Cookie", session, "X-CSRF-Token", token)).status()).isEqualTo(403);
            assertThat(client.execute(request("POST", "/notes?text=hello", "Cookie", session, "X-CSRF-Token", fresh)).status()).isEqualTo(204);
            assertThat(text(client.execute(request("GET", "/notes", "Cookie", session)))).isEqualTo("hello");

            var out = client.execute(request("POST", "/logout", "Cookie", session, "X-CSRF-Token", fresh));
            assertThat(out.headers().get("Set-Cookie")).contains("Max-Age=0");
            assertThat(client.execute(request("GET", "/me", "Cookie", session)).status()).isEqualTo(401);
        }
    }

    @Test
    void limitsLoginAttemptsPerClientAddress() throws Exception {
        try (var client = client()) {
            var page = client.execute(request("GET", "/csrf"));
            var token = text(page);
            var cookie = cookie(page);
            for (int attempt = 0; attempt < 5; attempt++) {
                assertThat(client.execute(request("POST", "/login?user=ada", "Cookie", cookie, "X-CSRF-Token", token, "X-Login-Code", "wrong")).status())
                        .isEqualTo(401);
            }
            var limited = client.execute(request("POST", "/login?user=ada", "Cookie", cookie, "X-CSRF-Token", token, "X-Login-Code", code));
            assertThat(limited.status()).isEqualTo(429);
            assertThat(limited.headers()).containsKey("Retry-After");
        }
    }

    @Test
    void secureHeadersAndRateLimitHeadersAccompanyResponses() throws Exception {
        try (var client = client()) {
            var response = client.execute(request("GET", "/csrf"));
            assertThat(response.headers()).containsEntry("X-Content-Type-Options", "nosniff").containsEntry("RateLimit-Limit", "300");
        }
    }
}
