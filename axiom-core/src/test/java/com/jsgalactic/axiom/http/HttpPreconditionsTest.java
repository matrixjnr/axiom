package com.jsgalactic.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.error.PreconditionRequiredException;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.routing.Route;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure evaluation and middleware behavior of conditional and range requests. */
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
        return Request.fromTarget(method, "/data").withHeaders(map);
    }

    private static String body(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : "null";
    }

    @Test
    void leavesOrdinaryAndStreamedResponsesAlone() {
        var plain = resource();
        assertThat(HttpPreconditions.evaluate(request("GET"), plain)).isSameAs(plain);
        var error = Response.of(404, "missing").withHeader("ETag", TAG);
        assertThat(HttpPreconditions.evaluate(request("GET", "If-None-Match", TAG), error)).isSameAs(error);
        var stream = Response.stream(200, "application/octet-stream", out -> out.write(new byte[] {1, 2, 3}));
        assertThat(HttpPreconditions.evaluate(request("GET", "Range", "bytes=0-1"), stream)).isSameAs(stream);
    }

    @Test
    void comparesEntityTagsWithTheStrongAndWeakRules() {
        assertThat(EntityTag.parse(TAG)).contains(new EntityTag(false, "v1"));
        assertThat(EntityTag.parse("W/" + TAG)).contains(new EntityTag(true, "v1"));
        assertThat(EntityTag.parse("\"v1\", \"v2\"")).isEmpty();
        assertThat(EntityTag.parse(" * ")).isEmpty();
        assertThat(EntityTag.parse("\"a b\"")).isEmpty();
        assertThat(EntityTag.parse("\"comma,inside\"")).contains(new EntityTag(false, "comma,inside"));
        assertThat(new EntityTag(false, "v1").strongMatches(new EntityTag(true, "v1"))).isFalse();
        assertThat(new EntityTag(false, "v1").weakMatches(new EntityTag(true, "v1"))).isTrue();
        assertThat(EntityTag.parse(TAG).orElseThrow().toString()).isEqualTo(TAG);
        assertThat(EntityTag.parse("W/" + TAG).orElseThrow().toString()).isEqualTo("W/" + TAG);
    }

    @Test
    void evaluatesIfMatchAndIfNoneMatchWithTheCorrectStatuses() {
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Match", TAG), resource()).status()).isEqualTo(200);
        for (var value : new String[] {"\"v2\"", "W/" + TAG, "garbage", "*x"}) {
            var rejected = HttpPreconditions.evaluate(request("GET", "If-Match", value), resource());
            assertThat(rejected.status()).as(value).isEqualTo(412);
            assertThat(rejected.body()).isNull();
        }
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Match", "*"), resource()).status()).isEqualTo(200);

        for (var value : new String[] {TAG, "W/" + TAG, "*", "\"other\", " + TAG}) {
            var cached = HttpPreconditions.evaluate(request("GET", "If-None-Match", value), resource());
            assertThat(cached.status()).as(value).isEqualTo(304);
            assertThat(cached.body()).isNull();
            assertThat(cached.headers()).containsEntry("ETag", TAG).containsEntry("Cache-Control", "max-age=60")
                    .doesNotContainKey("Content-Type");
        }
        assertThat(HttpPreconditions.evaluate(request("GET", "If-None-Match", "\"v2\""), resource()).status())
                .isEqualTo(200);
        assertThat(HttpPreconditions.evaluate(request("POST", "If-None-Match", TAG), resource()).status())
                .isEqualTo(412);
        assertThat(HttpPreconditions.evaluate(request("POST", "If-Match", TAG), resource()).status())
                .isEqualTo(200);
    }

    @Test
    void followsRfcPrecedenceWhenSeveralFieldsArePresent() {
        assertThat(HttpPreconditions.evaluate(request("GET",
                "If-Match", "\"v2\"", "If-None-Match", TAG), resource()).status()).isEqualTo(412);
        assertThat(HttpPreconditions.evaluate(request("GET",
                "If-Unmodified-Since", EARLIER, "If-None-Match", TAG), resource()).status()).isEqualTo(412);
        assertThat(HttpPreconditions.evaluate(request("GET",
                "If-Match", TAG, "If-Unmodified-Since", EARLIER), resource()).status()).isEqualTo(200);
        assertThat(HttpPreconditions.evaluate(request("GET",
                "If-None-Match", TAG, "If-Modified-Since", EARLIER), resource()).status()).isEqualTo(304);
        // If-None-Match replaces If-Modified-Since even when the entity tag does not match.
        assertThat(HttpPreconditions.evaluate(request("GET",
                "If-None-Match", "\"v2\"", "If-Modified-Since", LATER), resource()).status()).isEqualTo(200);
    }

    @Test
    void evaluatesDateConditionsAndIgnoresUnusableDates() {
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Modified-Since", LATER), resource()).status())
                .isEqualTo(304);
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Modified-Since", MODIFIED), resource()).status())
                .isEqualTo(304);
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Modified-Since", EARLIER), resource()).status())
                .isEqualTo(200);
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Unmodified-Since", EARLIER), resource()).status())
                .isEqualTo(412);
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Unmodified-Since", LATER), resource()).status())
                .isEqualTo(200);
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Modified-Since", "not a date"), resource()).status())
                .isEqualTo(200);
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Modified-Since", "Wed, 21 Xyz 2015 07:28:00 GMT"),
                resource()).status()).isEqualTo(200);
        assertThat(HttpPreconditions.evaluate(request("POST", "If-Modified-Since", LATER), resource()).status())
                .isEqualTo(200);
        var withoutDate = Response.of(200, new byte[] {1}).withHeader("ETag", TAG);
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Modified-Since", LATER), withoutDate).status())
                .isEqualTo(200);
    }

    @Test
    void acceptsAllThreeHttpDateFormatsForIfModifiedSince() {
        for (var date : new String[] {MODIFIED, "Wednesday, 21-Oct-15 07:28:00 GMT", "Wed Oct 21 07:28:00 2015"}) {
            assertThat(HttpPreconditions.evaluate(request("GET", "If-Modified-Since", date), resource()).status())
                    .as(date).isEqualTo(304);
        }
    }

    @Test
    void servesOneRangeWithContentRangeAndAcceptRanges() {
        for (var spec : new String[] {"bytes=2-5", "bytes=6-99", "bytes=7-", "bytes=-4", "bytes=-100"}) {
            var response = HttpPreconditions.evaluate(request("GET", "Range", spec), resource());
            assertThat(response.status()).as(spec).isEqualTo(206);
            assertThat(response.headers()).containsEntry("Accept-Ranges", "bytes");
            assertThat(body(response)).as(spec).isEqualTo(switch (spec) {
                case "bytes=2-5" -> "2345";
                case "bytes=6-99", "bytes=-4" -> "6789";
                case "bytes=7-" -> "789";
                case "bytes=-100" -> "0123456789";
                default -> throw new AssertionError(spec);
            });
        }
        var first = HttpPreconditions.evaluate(request("GET", "Range", "bytes=2-5"), resource());
        assertThat(first.headers()).containsEntry("Content-Range", "bytes 2-5/10")
                .containsEntry("Content-Type", "application/octet-stream");
    }

    @Test
    void servesSeveralRangesAsMultipartByteranges() {
        var response = HttpPreconditions.evaluate(request("GET", "Range", "bytes=0-1,4-5"), resource());
        assertThat(response.status()).isEqualTo(206);
        assertThat(response.headers()).containsEntry("Content-Type",
                "multipart/byteranges; boundary=" + HttpPreconditions.BOUNDARY)
                .containsEntry("Accept-Ranges", "bytes");
        assertThat(body(response)).isEqualTo("--" + HttpPreconditions.BOUNDARY + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Range: bytes 0-1/10\r\n\r\n01\r\n"
                + "--" + HttpPreconditions.BOUNDARY + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Range: bytes 4-5/10\r\n\r\n45\r\n"
                + "--" + HttpPreconditions.BOUNDARY + "--\r\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=10-", "bytes=-0", "bytes=5-2", "bytes=", "items=0-1", "bytes=0-1,",
            "bytes=0-1, x", "bytes=0 - 1", "bytes=+1-2", "bytes=999999999999999999999-", "bytes=0-1 "})
    void answersUnsatisfiableAndMalformedRangesWith416(String spec) {
        var response = HttpPreconditions.evaluate(request("GET", "Range", spec), resource());
        assertThat(response.status()).as(spec).isEqualTo(416);
        assertThat(response.body()).isNull();
        assertThat(response.headers()).containsEntry("Content-Range", "bytes */10")
                .containsEntry("Accept-Ranges", "bytes");
    }

    @Test
    void boundsTheNumberOfRanges() {
        assertThat(HttpPreconditions.evaluate(request("GET", "Range", "bytes=0-0,1-1,2-2,3-3,4-4,5-5,6-6,7-7"),
                resource()).status()).isEqualTo(206);
        assertThat(HttpPreconditions.evaluate(request("GET", "Range", "bytes=0-0,1-1,2-2,3-3,4-4,5-5,6-6,7-7,8-8"),
                resource()).status()).isEqualTo(416);
    }

    @Test
    void ignoresRangeWhenIfRangeDoesNotMatch() {
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Range", TAG, "Range", "bytes=0-1"), resource())
                .status()).isEqualTo(206);
        for (var value : new String[] {"\"v2\"", "W/" + TAG, "not a validator", LATER, EARLIER}) {
            var response = HttpPreconditions.evaluate(request("GET", "If-Range", value, "Range", "bytes=0-1"),
                    resource());
            assertThat(response.status()).as(value).isEqualTo(200);
            assertThat(body(response)).isEqualTo("0123456789");
        }
        assertThat(HttpPreconditions.evaluate(request("GET", "If-Range", MODIFIED, "Range", "bytes=0-1"), resource())
                .status()).isEqualTo(206);
    }

    @Test
    void middlewareEvaluatesTheResponseReturnedByTheChain() throws Exception {
        var context = new TestContext(request("GET", "Range", "bytes=0-1"));
        var response = HttpPreconditions.conditional().handle(context, HttpPreconditionsTest::resource);
        assertThat(response.status()).isEqualTo(206);
        assertThat(body(response)).isEqualTo("01");
    }

    @Test
    void preconditionRequiredOnlyRefusesUnsafeMethodsWithoutACondition() throws Exception {
        var conditional = HttpPreconditions.preconditionRequired();
        assertThatThrownBy(() -> conditional.handle(new TestContext(request("POST")), HttpPreconditionsTest::resource))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> conditional.handle(new TestContext(request("PATCH")), HttpPreconditionsTest::resource))
                .isInstanceOf(PreconditionRequiredException.class);
        for (var request : new Request[] {
                request("GET"), request("HEAD"), request("OPTIONS"), request("TRACE"),
                request("PUT", "If-Match", TAG), request("DELETE", "If-Unmodified-Since", LATER),
                request("POST", "If-None-Match", TAG)}) {
            assertThatCode(() -> conditional.handle(new TestContext(request), HttpPreconditionsTest::resource))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void parsesByteRangesWithoutOverflow() {
        assertThat(ByteRanges.parse("bytes=0-1, 3-4", 10)).get().satisfies(ranges -> {
            assertThat(ranges.ranges()).containsExactly(new ByteRange(0, 1), new ByteRange(3, 4));
            assertThat(ranges.ranges().getFirst().length()).isEqualTo(2);
        });
        assertThat(ByteRanges.parse("bytes=-99999999999999999999999", 10)).get()
                .satisfies(ranges -> assertThat(ranges.ranges()).containsExactly(new ByteRange(0, 9)));
        assertThat(ByteRanges.parse("bytes=99999999999999999999999-", 10)).isEmpty();
        assertThat(ByteRanges.parse(null, 10)).isEmpty();
        assertThat(ByteRanges.parse("bytes=0-0", 0)).isEmpty();
    }

    /** Minimal context for calling middleware without a runtime provider. */
    private static final class TestContext implements Context {
        private final Request request;

        private TestContext(Request request) {
            this.request = request;
        }

        @Override public Request request() { return request; }

        @Override public ExecutionContext execution() { throw new UnsupportedOperationException(); }

        @Override public Context status(int status) { return this; }

        @Override public Route route() { throw new UnsupportedOperationException(); }

        @Override public String path(String name) { throw new UnsupportedOperationException(); }

        @Override public Map<String, String> pathParameters() { return Map.of(); }

        @Override public <T> T body(Class<T> type) { throw new UnsupportedOperationException(); }

        @Override public Response response(Object body) { return Response.of(200, body); }
    }
}
