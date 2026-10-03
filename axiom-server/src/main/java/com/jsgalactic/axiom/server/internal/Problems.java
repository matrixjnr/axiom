package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.error.AxiomException;
import com.jsgalactic.axiom.error.Violation;
import com.jsgalactic.axiom.http.HttpStatus;
import com.jsgalactic.axiom.http.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds RFC 9457 style {@code application/problem+json} error responses. The body contains
 * only {@code status}, {@code code}, {@code requestId} and, when present, {@code violations} (each with a {@code code} and, unless it concerns the whole
 * value, a {@code field});
 * every value is a validated code, field path or framework identity, so no client input,
 * exception message or class name can reach it. Shared by the in-memory runtime, the HTTP
 * transport and the test client so all three answer identically. Not application API.
 */
public final class Problems {
    /** Media type of every framework error response. */
    public static final String MEDIA_TYPE = "application/problem+json";

    private Problems() {}

    /**
     * Maps an application or framework exception, including its typed headers.
     *
     * @param failure exception to map
     * @param requestId framework request identity
     * @return error response
     */
    public static Response response(AxiomException failure, String requestId) {
        var response = response(failure.status(), failure.code(), requestId, failure.violations());
        for (var header : failure.headers().entrySet()) {
            response = response.withHeader(header.getKey(), header.getValue());
        }
        return response;
    }

    /**
     * Builds an error response with the status's default code.
     *
     * @param status error status, 400 through 599
     * @param requestId framework request identity
     * @return error response
     */
    public static Response response(int status, String requestId) {
        return response(status, HttpStatus.defaultCode(status), requestId, List.of());
    }

    private static Response response(int status, String code, String requestId, List<Violation> violations) {
        return Response.of(status, body(status, code, requestId, violations)).withHeader("Content-Type", MEDIA_TYPE);
    }

    /**
     * Serializes the problem document.
     *
     * @param status error status
     * @param code safe error code
     * @param requestId framework request identity
     * @param violations validated field violations
     * @return UTF-8 JSON
     */
    public static byte[] body(int status, String code, String requestId, List<Violation> violations) {
        var json = new StringBuilder(96).append("{\"status\":").append(status)
                .append(",\"code\":");
        string(json, code).append(",\"requestId\":");
        string(json, requestId);
        if (!violations.isEmpty()) {
            json.append(",\"violations\":[");
            for (int i = 0; i < violations.size(); i++) {
                if (i > 0) { json.append(','); }
                var violation = violations.get(i);
                json.append('{');
                if (!violation.field().isEmpty()) { string(json.append("\"field\":"), violation.field()).append(','); }
                string(json.append("\"code\":"), violation.code()).append('}');
            }
            json.append(']');
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Escapes defensively although every value is already restricted to safe characters. */
    private static StringBuilder string(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') { json.append('\\').append(c); }
            else if (c < 0x20 || c > 0x7e) { json.append(String.format("\\u%04x", (int) c)); }
            else { json.append(c); }
        }
        return json.append('"');
    }
}
