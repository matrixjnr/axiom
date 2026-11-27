package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.http.HttpPreconditions;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Conditional and range requests through the real dispatcher and the in-memory test client. */
class HttpPreconditionsTest {
    private static final String TAG = "\"v1\"";
    private static final String MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT";
    private static final String LATER = "Thu, 22 Oct 2015 07:28:00 GMT";
    private static final String EARLIER = "Tue, 20 Oct 2015 07:28:00 GMT";

    private static Response resource() {
        return Response.of(200, "0123456789".getBytes(StandardCharsets.UTF_8))
                .withHeader("ETag", TAG)
                .withHeader("Last-Modified", MODIFIED)
                .withHeader("Cache-Control", "max-age=60");
    }

    private static Request request(String method, String... headers) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < headers.length; i += 2) { map.put(headers[i], headers[i + 1]); }
        return Request.fromTarget(method, "/bytes").withHeaders(map);
    }

    private static String body(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : "null";
    }

    private static Application application() {
        var app = Axiom.create();
        app.use(HttpPreconditions.conditional());
        app.use(HttpPreconditions.preconditionRequired());
        app.get("/bytes", ctx -> resource());
        app.post("/bytes", ctx -> resource());
        app.get("/stream", ctx -> Response.stream(200, "application/octet-stream",
                out -> out.write(new byte[] {1, 2, 3, 4})));
        return app;
    }

    @Test
    void conditionalFieldsAnswer200304And412ThroughTheDispatcher() throws Exception {
        try (var client = TestClient.start(application())) {
            assertThat(client.execute(request("GET")).status()).isEqualTo(200);
            assertThat(client.execute(request("GET", "If-Match", TAG)).status()).isEqualTo(200);
            assertThat(client.execute(request("GET", "If-Match", "W/" + TAG)).status()).isEqualTo(412);
            assertThat(client.execute(request("GET", "If-None-Match", "W/" + TAG)).status()).isEqualTo(304);
            var cached = client.execute(request("GET", "If-None-Match", TAG));
            assertThat(cached.status()).isEqualTo(304);
            assertThat(cached.body()).isNull();
            assertThat(cached.headers()).containsEntry("ETag", TAG).containsEntry("Cache-Control", "max-age=60");
            assertThat(client.execute(request("GET", "If-Modified-Since", LATER)).status()).isEqualTo(304);
            assertThat(client.execute(request("GET", "If-Unmodified-Since", EARLIER)).status()).isEqualTo(412);
        }
    }

    @Test
    void rangesAnswer206SingleMultipartAnd416ThroughTheDispatcher() throws Exception {
        try (var client = TestClient.start(application())) {
            var single = client.execute(request("GET", "Range", "bytes=2-5"));
            assertThat(single.status()).isEqualTo(206);
            assertThat(single.headers()).containsEntry("Content-Range", "bytes 2-5/10")
                    .containsEntry("Accept-Ranges", "bytes");
            assertThat(body(single)).isEqualTo("2345");

            var suffix = client.execute(request("GET", "Range", "bytes=-3"));
            assertThat(suffix.status()).isEqualTo(206);
            assertThat(suffix.headers()).containsEntry("Content-Range", "bytes 7-9/10");
            assertThat(body(suffix)).isEqualTo("789");

            var several = client.execute(request("GET", "Range", "bytes=0-1,4-5"));
            assertThat(several.status()).isEqualTo(206);
            assertThat(several.headers().get("Content-Type")).startsWith("multipart/byteranges; boundary=");
            assertThat(body(several)).contains("Content-Range: bytes 0-1/10", "Content-Range: bytes 4-5/10");

            var missing = client.execute(request("GET", "Range", "bytes=10-"));
            assertThat(missing.status()).isEqualTo(416);
            assertThat(missing.body()).isNull();
            assertThat(missing.headers()).containsEntry("Content-Range", "bytes */10");

            var tooMany = client.execute(request("GET", "Range", "bytes=0-0,1-1,2-2,3-3,4-4,5-5,6-6,7-7,8-8"));
            assertThat(tooMany.status()).isEqualTo(416);

            var ignored = client.execute(request("GET", "If-Range", "\"old\"", "Range", "bytes=0-1"));
            assertThat(ignored.status()).isEqualTo(200);
            assertThat(body(ignored)).isEqualTo("0123456789");
            var matched = client.execute(request("GET", "If-Range", TAG, "Range", "bytes=0-1"));
            assertThat(matched.status()).isEqualTo(206);
            assertThat(body(matched)).isEqualTo("01");
        }
    }

    @Test
    void unsafeMethodsWithoutPreconditionsAnswer428() throws Exception {
        try (var client = TestClient.start(application())) {
            var refused = client.execute(request("POST"));
            assertThat(refused.status()).isEqualTo(428);
            assertThat(body(refused)).contains("\"code\":\"precondition_required\"");
            assertThat(client.execute(request("POST", "If-Match", TAG)).status()).isEqualTo(200);
        }
    }

    @Test
    void streamedResponsesPassThroughUnchanged() throws Exception {
        try (var client = TestClient.start(application())) {
            var request = Request.fromTarget("GET", "/stream")
                    .withHeaders(Map.of("Range", "bytes=0-1", "If-None-Match", TAG));
            var response = client.execute(request);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headers()).doesNotContainKey("Content-Range");
            assertThat(body(response)).isEqualTo("\u0001\u0002\u0003\u0004");
        }
    }
}
