package io.axiom.context;

import io.axiom.error.BadRequestException;
import io.axiom.error.ValidationException;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.routing.Route;
import io.axiom.internal.PercentDecoding;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Request-scoped view and response settings for one handler invocation.
 * <p>
 * A context is <em>thread-confined</em>: use it only on the thread that invoked the handler
 * and only until the handler returns. It holds mutable response settings ({@link #status(int)})
 * without synchronization and must not be shared with, or retained by, other threads.
 * To hand work to application tasks, pass the immutable {@link #execution()} and
 * {@link #request()} values or the extracted captures instead.
 */
public interface Context {
    /**
     * Returns the immutable request metadata.
     *
     * @return the immutable request metadata
     */
    Request request();

    /**
     * Returns immutable identity and deadline metadata for this invocation.
     * @return execution context, safe to share with application tasks
     */
    ExecutionContext execution();
    /**
     * Returns the case-sensitive HTTP method.
     *
     * @return the case-sensitive HTTP method
     */
    default String method() {
        return request().method();
    }

    /**
     * Returns the raw path, without decoding or normalization.
     *
     * @return the raw path, without decoding or normalization
     */
    default String path() {
        return request().path();
    }

    /**
     * Returns a request header value, matching the name case-insensitively. Header values are
     * untrusted client input.
     *
     * @param name header name
     * @return value, if present
     */
    default Optional<String> header(String name) {
        return request().header(name);
    }

    /**
     * Returns the first value of a query parameter, percent-decoded as strict UTF-8 with
     * {@code +} decoded as a space. Names match exactly after decoding. A parameter without
     * {@code =} has an empty value. The query was validated when the request was created, so
     * decoding cannot fail here. Values are untrusted client input.
     *
     * @param name decoded parameter name
     * @return first value, if the parameter is present
     * @see Request#query(String)
     */
    default Optional<String> query(String name) {
        return request().query(name);
    }

    /**
     * Returns every value of a query parameter in request order, decoded as by
     * {@link #query(String)}.
     *
     * @param name decoded parameter name
     * @return immutable values; empty when the parameter is absent
     * @see Request#queryAll(String)
     */
    default List<String> queryAll(String name) {
        return request().queryAll(name);
    }

    /**
     * Decodes the request body with the installed codec for its Content-Type.
     * <p>
     * Checks run in this order: an empty or missing body fails with
     * {@link io.axiom.error.DecodeException} ({@code empty_body}, 400); a missing or malformed
     * Content-Type fails with {@link io.axiom.error.UnsupportedMediaTypeException}
     * ({@code missing_content_type}, 415); a {@code charset} parameter other than UTF-8 fails with
     * code {@code unsupported_charset} (415); a media type without an installed codec fails with
     * {@code unsupported_media_type} (415); content the codec cannot decode into the type fails
     * with a {@code DecodeException} (400). These exceptions become error responses when they
     * leave the handler. The body has already passed the application's size limit.
     *
     * @param type target type, for example a record
     * @param <T> target type
     * @return decoded value, never null
     */
    <T> T body(Class<T> type);

    /**
     * Decodes the request body as {@link #body(Class)} does, then checks it.
     *
     * <pre>{@code
     * var order = ctx.validatedBody(Order.class, ORDER_RULES);
     * }</pre>
     *
     * Decoding failures come first and the validator then does not run. When the validator
     * reports violations this throws {@link ValidationException}, answered 422 with a problem
     * body that lists only each violation's field and code (at most the first 100).
     *
     * @param type target type, for example a record
     * @param validator thread-safe check, such as an {@code axiom-validation} {@code Validator}
     * @param <T> target type
     * @return the decoded, valid value, never null
     * @throws ValidationException if the validator reports violations
     * @throws IllegalStateException if the validator returns null or a null violation
     */
    default <T> T validatedBody(Class<T> type, BodyValidator<? super T> validator) {
        Objects.requireNonNull(validator, "validator");
        var value = body(type);
        var violations = validator.validate(value);
        if (violations == null) {
            throw new IllegalStateException("Validator returned null instead of a violation list");
        }
        for (var violation : violations) {
            if (violation == null) { throw new IllegalStateException("Validator returned a null violation"); }
        }
        if (violations.isEmpty()) { return value; }
        // ValidationException accepts at most 100 violations.
        throw new ValidationException(violations.size() > 100 ? violations.subList(0, 100) : violations);
    }

    /**
     * Maps a value to a JSON response using the current status (200 by default). The value is
     * encoded by the installed {@code application/json} codec when the response is prepared,
     * after the handler returns. A {@code String} or {@code byte[]} value is sent verbatim as
     * already-encoded JSON. Without a JSON codec installed the response cannot be sent and the
     * listener answers 500.
     *
     * @param value non-null value to encode
     * @return response with {@code Content-Type: application/json}
     * @throws IllegalStateException if the status set on this context cannot carry a body
     */
    default Response json(Object value) {
        return response(java.util.Objects.requireNonNull(value, "value"))
                .withHeader("Content-Type", "application/json");
    }

    /**
     * Sets the status for subsequent response mapping. Statuses 204, 205, and 304 cannot carry
     * a body: after setting one, mapping a non-null body (returning it from the handler or
     * calling {@link #response(Object)} or {@link #text(String)}) fails with an
     * {@link IllegalStateException} that names the route and status. Over HTTP that failure
     * is a 500 like any other handler failure.
     *
     * @param status final HTTP status (200-599)
     * @return this context
     * @throws IllegalArgumentException if the status is not a final status
     */
    Context status(int status);

    /**
     * Returns the matched route identity, including its template rather than request values.
     * Handlers, group middleware and route middleware always have one. Global middleware also run
     * for answers the router produces itself (404, 405, automatic OPTIONS, 501), where no route
     * matched; there this method throws, so global middleware should use {@link #matchedRoute()}.
     * @return matched route
     * @throws IllegalStateException if no route matched this request
     */
    Route route();

    /**
     * Returns the matched route identity, or empty when the router answers the request itself
     * (404, 405, automatic OPTIONS, {@code OPTIONS *}, 501). Never throws, so global middleware
     * can use it for every request, for example to log the route template.
     * @return matched route, if any
     */
    default Optional<Route> matchedRoute() {
        return Optional.of(route());
    }

    /**
     * Reads a raw path capture without percent-decoding or normalization.
     * Captures are untrusted client input. A wildcard remainder may contain {@code /} and
     * must not be used as a file system path without the application's own containment checks.
     * @param name capture name declared in the route template
     * @return captured segment or wildcard remainder (which may be empty)
     * @throws IllegalArgumentException if the name is not declared by the matched route
     */
    String path(String name);

    /**
     * Reads a path capture with strict, single-pass UTF-8 percent-decoding. Each segment is
     * decoded once; a decoded value is never decoded again. Wildcard remainders keep their
     * raw {@code /} separators. Decoding fails rather than producing a {@code /}, backslash,
     * or NUL inside a segment, a {@code .} or {@code ..} segment, or malformed UTF-8.
     * The result is still untrusted client input.
     * A capture that cannot be decoded safely is the client's error: the failure is a
     * {@link BadRequestException} with code {@code invalid_path_encoding}, answered 400 like
     * any other {@link io.axiom.error.AxiomException}; it never carries the capture.
     * @param name capture name declared in the route template
     * @return decoded capture
     * @throws IllegalArgumentException if the name is not declared by the matched route
     * @throws BadRequestException if the value is malformed UTF-8 or would decode to a
     *         separator, NUL or dot segment
     */
    default String pathDecoded(String name) {
        var raw = path(name);
        var decoded = new StringBuilder(raw.length());
        int start = 0;
        while (true) {
            int end = raw.indexOf('/', start);
            decoded.append(decodeSegment(raw, start, end < 0 ? raw.length() : end));
            if (end < 0) { return decoded.toString(); }
            decoded.append('/');
            start = end + 1;
        }
    }

    private static String decodeSegment(String raw, int start, int end) {
        String segment;
        try {
            segment = PercentDecoding.decode(raw, start, end, false);
        } catch (IllegalArgumentException malformed) {
            var failure = new BadRequestException("invalid_path_encoding");
            failure.initCause(malformed); // For logs only; its message never contains the input.
            throw failure;
        }
        if (segment.equals(".") || segment.equals("..") || segment.indexOf('/') >= 0
                || segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0) {
            throw new BadRequestException("invalid_path_encoding");
        }
        return segment;
    }

    /**
     * Returns all raw captures in template order. The map is extracted when the route
     * matches and is immutable, so it may be shared with other threads.
     * @return immutable parameter map; empty for a static route
     */
    Map<String, String> pathParameters();

    /**
     * Maps a body using the current status (200 by default).
     * Strings use UTF-8 text and byte arrays use application/octet-stream.
     * Other objects are retained without a Content-Type and are not encoded; use
     * {@link #json(Object)} to encode them with the JSON codec.
     * Null without an explicit status produces 204.
     * @param body returned body, or null
     * @return a response snapshot
     * @throws IllegalStateException if the status set on this context cannot carry a body
     */
    Response response(Object body);

    /**
     * Creates a text response using the current settings.
     *
     * @param text non-null text
     * @return a response using the current status
     */
    default Response text(String text) {
        return response(java.util.Objects.requireNonNull(text, "text"));
    }

    /**
     * Returns a redirect without a body. Use {@link Response#withLocation(String)} for 201
     * Created, for example {@code ctx.status(201).json(item).withLocation("/items/" + id)}.
     *
     * @param status 301, 302, 303, 307 or 308
     * @param location target URI reference, validated against header injection
     * @return redirect response
     * @throws IllegalArgumentException for another status or an invalid location
     */
    default Response redirect(int status, String location) {
        return Response.redirect(status, location);
    }

    /**
     * Returns a 204 response with no body.
     *
     * @return a 204 response with no body
     */
    default Response noContent() {
        return Response.of(204, null);
    }
}
