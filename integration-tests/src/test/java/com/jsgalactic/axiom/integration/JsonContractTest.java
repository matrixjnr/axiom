package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.integration.Exchange.Client;
import com.jsgalactic.axiom.integration.Exchange.Reply;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * JSON behavior with the real Jackson codec, run once through {@code TestClient} and once over a
 * live listener by the subclasses. Bad inputs carry the marker {@code POISON}, a markup fragment
 * and a class name, none of which may reach a response.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class JsonContractTest {
    private static final String JSON = "application/json";
    private static final String POISON = "POISON<script>java.lang.Runtime";
    /** The complete problem document: no other member, and only safe codes and paths. */
    private static final Pattern PROBLEM = Pattern.compile("\\{\"status\":(\\d{3}),\"code\":\"([a-z_]+)\","
            + "\"requestId\":\"[0-9A-Za-z._:-]+\"(?:,\"violations\":\\[\\{\"field\":\"([A-Za-z0-9_.\\[\\]]+)\","
            + "\"code\":\"([a-z_]+)\"\\}\\])?\\}");
    private static final List<String> LEAKS = List.of("POISON", "script", "Runtime", "jackson", "Jackson",
            "fasterxml", "Exception", "com.jsgalactic.axiom", "java.", "Order", "Line", "Source", "line:", "column:",
            "Unrecognized", "Duplicate", "Trailing", "Cannot", "Unexpected");

    private Client client;

    /** Opens a client for a fresh {@link OrdersApi}. */
    abstract Client open() throws Exception;

    @BeforeAll void start() throws Exception { client = open(); }

    @AfterAll void stop() throws Exception { if (client != null) { client.close(); } }

    private Reply post(String contentType, byte[] body) throws Exception {
        var headers = new LinkedHashMap<String, String>();
        if (contentType != null) { headers.put("Content-Type", contentType); }
        return client.send("POST", "/orders", headers, body);
    }

    private Reply post(String json) throws Exception { return post(JSON, json.getBytes(StandardCharsets.UTF_8)); }

    private Reply sample(String accept) throws Exception {
        return client.send("GET", "/orders/sample", accept == null ? Map.of() : Map.of("Accept", accept), new byte[0]);
    }

    /** The sample order with one member's JSON value replaced. */
    private static String sampleWith(String member, String json) {
        var pattern = Pattern.compile("\"" + member + "\":(\"[^\"]*\"|\\[[^\\]]*\\]|\\{[^}]*\\}|[^,}]+)");
        var matcher = pattern.matcher(OrdersApi.SAMPLE_JSON);
        assertThat(matcher.find()).as("member %s", member).isTrue();
        return OrdersApi.SAMPLE_JSON.substring(0, matcher.start()) + "\"" + member + "\":" + json
                + OrdersApi.SAMPLE_JSON.substring(matcher.end());
    }

    private static void assertProblem(Reply reply, int status, String code, String field) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(reply.contentType()).isEqualTo("application/problem+json");
        var matcher = PROBLEM.matcher(reply.body());
        assertThat(matcher.matches()).as("problem shape of %s", reply.body()).isTrue();
        assertThat(matcher.group(1)).isEqualTo(Integer.toString(status));
        assertThat(matcher.group(2)).isEqualTo(code);
        assertThat(matcher.group(3)).isEqualTo(field);
        if (field != null) { assertThat(matcher.group(4)).isEqualTo(code); }
        assertThat(reply.body()).doesNotContain(LEAKS);
    }

    @Test void roundTripsARecordWithJavaTimeAndCommonTypes() throws Exception {
        var created = post(OrdersApi.SAMPLE_JSON);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.contentType()).isEqualTo(JSON);
        assertThat(created.body()).isEqualTo(OrdersApi.SAMPLE_JSON);

        var sample = sample(null);
        assertThat(sample.status()).isEqualTo(200);
        assertThat(sample.body()).isEqualTo(OrdersApi.SAMPLE_JSON);
    }

    @Test void readsMissingOptionalAsEmptyAndWritesItAsNull() throws Exception {
        var reply = post(OrdersApi.SAMPLE_JSON.replace(",\"note\":\"ring twice\"", ""));
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.body()).contains("\"note\":null");
    }

    @Test void rejectsUnknownFieldsWithoutNamingThem() throws Exception {
        assertProblem(post(OrdersApi.SAMPLE_JSON.replace("{\"id\"", "{\"" + POISON + "\":1,\"id\"")),
                400, "unknown_field", null);
        assertProblem(post(sampleWith("lines", "[{\"sku\":\"pen\",\"quantity\":1,\"weight\":1,\"" + POISON + "\":1}]")),
                400, "unknown_field", "lines[0]");
    }

    @Test void rejectsDuplicateKeysAtAnyDepth() throws Exception {
        assertProblem(post(OrdersApi.SAMPLE_JSON.replace("\"customer\":\"Ada\"",
                "\"customer\":\"Ada\",\"customer\":\"" + POISON + "\"")), 400, "duplicate_field", null);
        assertProblem(post(sampleWith("attributes", "{\"" + POISON + "\":1,\"" + POISON + "\":2}")),
                400, "duplicate_field", null);
    }

    @Test void rejectsTrailingContent() throws Exception {
        assertProblem(post(OrdersApi.SAMPLE_JSON + " {\"" + POISON + "\":1}"), 400, "trailing_content", null);
        assertProblem(post(OrdersApi.SAMPLE_JSON + "\"" + POISON + "\""), 400, "trailing_content", null);
    }

    @Test void boundsNestingDepth() throws Exception {
        var bomb = "{\"a\":".repeat(10_000) + "\"" + POISON + "\"" + "}".repeat(10_000);
        assertProblem(post(sampleWith("attributes", bomb)), 400, "limit_exceeded", null);
    }

    @Test void boundsStringLength() throws Exception {
        var huge = "\"" + POISON + "x".repeat(1024 * 1024) + "\"";
        assertProblem(post(sampleWith("customer", huge)), 400, "limit_exceeded", null);
    }

    @Test void rejectsNumbersThatAreNotFinite() throws Exception {
        assertProblem(post(sampleWith("lines", "[{\"sku\":\"pen\",\"quantity\":1,\"weight\":1e400}]")),
                400, "type_mismatch", "lines[0].weight");
        assertProblem(post(sampleWith("lines", "[{\"sku\":\"pen\",\"quantity\":1,\"weight\":NaN}]")),
                400, "malformed_json", null);
    }

    @Test void reportsWrongTypesWithTheirPropertyPath() throws Exception {
        assertProblem(post(sampleWith("lines", "[{\"sku\":\"pen\",\"quantity\":\"" + POISON + "\",\"weight\":1}]")),
                400, "type_mismatch", "lines[0].quantity");
        assertProblem(post(sampleWith("lines", "[{\"sku\":7,\"quantity\":1,\"weight\":1}]")),
                400, "type_mismatch", "lines[0].sku");
        assertProblem(post(sampleWith("placedAt", "\"" + POISON + "\"")), 400, "type_mismatch", "placedAt");
        assertProblem(post(sampleWith("placedAt", "1700000000")), 400, "type_mismatch", "placedAt");
        assertProblem(post(sampleWith("deliverOn", "\"2024-02-30\"")), 400, "type_mismatch", "deliverOn");
        assertProblem(post(sampleWith("window", "\"" + POISON + "\"")), 400, "type_mismatch", "window");
        assertProblem(post(sampleWith("status", "\"" + POISON + "\"")), 400, "type_mismatch", "status");
        assertProblem(post(sampleWith("status", "1")), 400, "type_mismatch", "status");
        assertProblem(post(sampleWith("id", "\"" + POISON + "\"")), 400, "type_mismatch", "id");
        assertProblem(post(sampleWith("total", "\"12.50\"")), 400, "type_mismatch", "total");
    }

    @Test void reportsSyntaxErrorsAsMalformed() throws Exception {
        assertProblem(post("{\"customer\":" + POISON + "}"), 400, "malformed_json", null);
        assertProblem(post(sampleWith("lines", "[{\"sku\":\"pen\",]")), 400, "malformed_json", null);
    }

    @Test void answersUnsupportedMediaTypesWith415() throws Exception {
        var body = OrdersApi.SAMPLE_JSON.getBytes(StandardCharsets.UTF_8);
        assertProblem(post("text/plain", body), 415, "unsupported_media_type", null);
        assertProblem(post("application/" + "x-poison", body), 415, "unsupported_media_type", null);
        assertProblem(post(null, body), 415, "missing_content_type", null);
    }

    @Test void answersEmptyBodiesWith400() throws Exception {
        assertProblem(post(JSON, new byte[0]), 400, "empty_body", null);
        assertProblem(post("   "), 400, "empty_body", null);
        assertProblem(post("null"), 400, "null_body", null);
    }

    @Test void answersBodiesOverTheLimitWith413() throws Exception {
        assertProblem(client.sendOversized("/orders", JSON, OrdersApi.MAX_BODY + 1), 413, "content_too_large", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/json;q=0, */*", "application/xml, text/*",
            "application/json;charset=iso-8859-1"})
    void answersUnacceptableResponsesWith406(String accept) throws Exception {
        assertProblem(sample(accept), 406, "not_acceptable", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/*;q=0.5, text/html", "*/*;q=0.1, application/json",
            "application/json; charset=UTF-8", "application/json;q=2", ""})
    void servesAcceptableOrUnparseableAcceptHeaders(String accept) throws Exception {
        var reply = sample(accept);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isEqualTo(OrdersApi.SAMPLE_JSON);
    }

    @Test void treatsAcceptAsAdvisoryForUnsafeMethods() throws Exception {
        var headers = Map.of("Content-Type", JSON, "Accept", "text/html");
        var reply = client.send("POST", "/orders", headers, OrdersApi.SAMPLE_JSON.getBytes(StandardCharsets.UTF_8));
        // The handler created the order, so the client gets that answer rather than a 406 to retry.
        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json; charset=utf-8", "application/json;charset=\"UTF-8\"",
            "APPLICATION/JSON; Charset=Utf-8"})
    void acceptsUtf8CharsetParameters(String contentType) throws Exception {
        var reply = post(contentType, OrdersApi.SAMPLE_JSON.getBytes(StandardCharsets.UTF_8));
        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json; charset=iso-8859-1", "application/json; charset=utf8",
            "application/json; charset=utf-16"})
    void rejectsOtherCharsetsWith415(String contentType) throws Exception {
        assertProblem(post(contentType, OrdersApi.SAMPLE_JSON.getBytes(StandardCharsets.UTF_8)),
                415, "unsupported_charset", null);
    }

    @Test void decodesStrictUtf8() throws Exception {
        var accented = sampleWith("customer", "\"Zoë ☕\"");
        var reply = post(accented);
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.body()).contains("\"customer\":\"Zoë ☕\"");

        var bom = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
        var json = OrdersApi.SAMPLE_JSON.getBytes(StandardCharsets.UTF_8);
        var withBom = new byte[bom.length + json.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(json, 0, withBom, bom.length, json.length);
        assertThat(post(JSON, withBom).status()).isEqualTo(201);

        assertProblem(post(JSON, accented.getBytes(StandardCharsets.ISO_8859_1)), 400, "invalid_encoding", null);
        assertProblem(post(JSON, OrdersApi.SAMPLE_JSON.getBytes(StandardCharsets.UTF_16)), 400, "invalid_encoding", null);
    }
}
