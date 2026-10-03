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

    /** Frames of a stack trace included in a development response. */
    static final int DEBUG_FRAMES = 64;
    /** Causes included in a development response. */
    static final int DEBUG_CAUSES = 8;

    /**
     * Adds the development-mode {@code debug} member to a problem response: the exception's class,
     * message, stack frames and causes. Only used when the application opted in to development
     * errors, which it can do only for loopback listeners.
     *
     * @param problem a response built by this class
     * @param failure exception to describe
     * @return the response with the extra member
     */
    static Response withDebug(Response problem, Throwable failure) {
        var original = new String((byte[]) problem.body(), StandardCharsets.UTF_8);
        var json = new StringBuilder(original.length() + 512).append(original, 0, original.length() - 1)
                .append(",\"debug\":{\"type\":");
        string(json, failure.getClass().getName());
        if (failure.getMessage() != null) { string(json.append(",\"message\":"), failure.getMessage()); }
        json.append(",\"stack\":[");
        var frames = failure.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, DEBUG_FRAMES); i++) {
            if (i > 0) { json.append(','); }
            string(json, frames[i].toString());
        }
        json.append(']');
        var cause = failure.getCause();
        if (cause != null) {
            json.append(",\"causes\":[");
            for (int i = 0; cause != null && i < DEBUG_CAUSES; i++, cause = cause.getCause()) {
                if (i > 0) { json.append(','); }
                string(json.append("{\"type\":"), cause.getClass().getName());
                if (cause.getMessage() != null) { string(json.append(",\"message\":"), cause.getMessage()); }
                json.append('}');
            }
            json.append(']');
        }
        json.append("}}");
        var rebuilt = Response.of(problem.status(), json.toString().getBytes(StandardCharsets.UTF_8));
        for (var header : problem.headers().entrySet()) { rebuilt = rebuilt.withHeader(header.getKey(), header.getValue()); }
        return rebuilt;
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
