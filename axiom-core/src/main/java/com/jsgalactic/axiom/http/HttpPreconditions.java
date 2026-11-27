package com.jsgalactic.axiom.http;

import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.PreconditionRequiredException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in HTTP conditional and range request support (RFC 9110 sections 13 and
 * 14), built on the immutable response values Axiom already produces.
 *
 * <pre>{@code
 * app.use(HttpPreconditions.conditional());            // evaluates 304, 412, 206 and 416
 * app.use(HttpPreconditions.preconditionRequired());   // answers 428 for unsafe methods
 * }</pre>
 *
 * <p>{@link #conditional()} calls the rest of the chain and evaluates the
 * request against the response's {@code ETag} and {@code Last-Modified} fields;
 * the application supplies those validators. Precedence follows RFC 9110
 * section 13.2.2: {@code If-Match} (strong comparison, 412 on failure), then
 * {@code If-Unmodified-Since} when {@code If-Match} is absent (412), then
 * {@code If-None-Match} (weak comparison; 304 for GET and HEAD, 412 otherwise),
 * then {@code If-Modified-Since} for GET and HEAD when {@code If-None-Match} is
 * absent (304). A mismatching {@code If-Range} makes the middleware ignore
 * {@code Range} and keep the full 200 response.
 *
 * <p>{@code Range} is parsed strictly and only for GET: at most
 * {@link ByteRanges#MAX_RANGES} specifications, suffix and open-ended forms,
 * and integer overflow clamped to unsatisfiability. A single satisfiable range
 * produces 206 with {@code Content-Range}; several produce a
 * {@code multipart/byteranges} body with the fixed {@link #BOUNDARY}. A
 * malformed or unsatisfiable range produces 416 with an asterisk
 * {@code Content-Range} giving the representation's total length. Every range
 * response carries {@code Accept-Ranges: bytes}.
 *
 * <p>The evaluation applies to buffered responses after encoding: byte arrays,
 * and strings read as UTF-8 bytes. Streamed responses pass through unchanged
 * because a stream has no total length against which a byte range or
 * unsatisfiability can be defined; applications that need ranges over large
 * bodies must slice the data before streaming it. Responses with another
 * status, such as the router's own 404, are also left untouched.
 *
 * <p>Both middleware are immutable and thread-safe; one instance may serve
 * every request. {@link #evaluate(Request, Response)} is the same decision as
 * a pure function, for applications that call it directly instead of registering
 * middleware. The status responses it builds are bodiless and keep only the
 * response's cache validators, leaving problem responses to the application.
 */
public final class HttpPreconditions {
    /** Boundary of every multipart range body, chosen so responses are reproducible. */
    public static final String BOUNDARY = "axiom-byteranges-boundary";

    private static final List<String> VALIDATORS = List.of(
            "Content-Location", "ETag", "Expires", "Vary", "Cache-Control", "Last-Modified");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Pattern IMF_DATE = Pattern.compile(
            "([A-Za-z]{3}), (\\d{2}) ([A-Za-z]{3}) (\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) GMT");
    private static final Pattern OLD_DATE = Pattern.compile(
            "([A-Za-z]+), (\\d{2})-([A-Za-z]{3})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) GMT");
    private static final Pattern ASC_DATE = Pattern.compile(
            "([A-Za-z]{3}) ([A-Za-z]{3}) (\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2}) (\\d{4})");
    private static final String MONTHS = "JanFebMarAprMayJunJulAugSepOctNovDec";

    private static final Middleware CONDITIONAL = (context, next) -> evaluate(context.request(), next.run());
    private static final Middleware PRECONDITION_REQUIRED = (context, next) -> {
        var request = context.request();
        if (!SAFE_METHODS.contains(request.method())
                && request.header("If-Match").isEmpty()
                && request.header("If-None-Match").isEmpty()
                && request.header("If-Unmodified-Since").isEmpty()) {
            throw new PreconditionRequiredException();
        }
        return next.run();
    };

    private HttpPreconditions() {}

    /**
     * Returns the opt-in middleware that evaluates preconditions and ranges.
     * Register it globally or on a route with {@code app.use(...)}; it makes no
     * change to a response that no precondition or range applies to.
     *
     * @return the shared, stateless middleware
     */
    public static Middleware conditional() {
        return CONDITIONAL;
    }

    /**
     * Returns the opt-in middleware that refuses unsafe methods without a
     * precondition. Safe methods (GET, HEAD, OPTIONS and TRACE) and unsafe
     * methods carrying {@code If-Match}, {@code If-None-Match} or
     * {@code If-Unmodified-Since} continue to the rest of the chain. A refusal
     * is a {@link PreconditionRequiredException}, so the runtime builds the
     * standard problem response with code {@code precondition_required}.
     *
     * @return the shared, stateless middleware
     */
    public static Middleware preconditionRequired() {
        return PRECONDITION_REQUIRED;
    }

    /**
     * Evaluates the request's preconditions and ranges against a response.
     *
     * <p>The response is returned unchanged when it is not successful, is
     * streamed, or has no buffered bytes, and when no conditional field applies.
     * A failed precondition yields a bodiless 412 or 304 response carrying the
     * original cache validators. A GET with a satisfiable range yields 206; with
     * a malformed, excessive or unsatisfiable range it yields a bodiless 416
     * with an asterisk {@code Content-Range}. A mismatching {@code If-Range}
     * leaves the whole 200 response intact.
     *
     * @param request the request whose conditional fields are evaluated
     * @param response the response selected for the request
     * @return the response to send, never null
     */
    public static Response evaluate(Request request, Response response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        if (response.status() < 200 || response.status() >= 300 || response.isStreaming()) { return response; }

        var method = request.method();
        boolean cacheValidation = method.equals("GET") || method.equals("HEAD");
        var ifMatch = request.header("If-Match");
        if (ifMatch.isPresent() && !matches(ifMatch.get(), response, true)) {
            return bodiless(412, response);
        }
        var ifUnmodifiedSince = request.header("If-Unmodified-Since");
        if (ifMatch.isEmpty() && ifUnmodifiedSince.isPresent()) {
            var since = parseDate(ifUnmodifiedSince.get());
            var modified = lastModified(response);
            if (since.isPresent() && modified.isPresent() && modified.get().isAfter(since.get())) {
                return bodiless(412, response);
            }
        }
        var ifNoneMatch = request.header("If-None-Match");
        if (ifNoneMatch.isPresent() && matches(ifNoneMatch.get(), response, false)) {
            return bodiless(cacheValidation ? 304 : 412, response);
        }
        var ifModifiedSince = request.header("If-Modified-Since");
        if (cacheValidation && ifNoneMatch.isEmpty() && ifModifiedSince.isPresent()) {
            var since = parseDate(ifModifiedSince.get());
            var modified = lastModified(response);
            if (since.isPresent() && modified.isPresent() && !modified.get().isAfter(since.get())) {
                return bodiless(304, response);
            }
        }

        var range = request.header("Range");
        if (range.isEmpty() || !method.equals("GET")) { return response; }
        var ifRange = request.header("If-Range");
        if (ifRange.isPresent() && !ifRangeMatches(ifRange.get(), response)) { return response; }
        byte[] bytes = buffered(response);
        if (bytes == null || response.status() != 200) { return response; }
        var parsed = ByteRanges.parse(range.get(), bytes.length);
        if (parsed.isEmpty()) { return unsatisfiable(response, bytes.length); }
        return parsed.get().ranges().size() == 1
                ? single(response, bytes, parsed.get().ranges().getFirst())
                : multipart(response, bytes, parsed.get().ranges());
    }

    private static boolean matches(String value, Response response, boolean strong) {
        if (value.equals("*")) { return true; }
        var candidates = EntityTag.parseList(value);
        var actual = header(response, "ETag").flatMap(EntityTag::parse);
        if (candidates.isEmpty() || actual.isEmpty()) { return false; }
        for (var candidate : candidates.get()) {
            if (strong ? actual.get().strongMatches(candidate) : actual.get().weakMatches(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ifRangeMatches(String value, Response response) {
        var tag = EntityTag.parse(value);
        if (tag.isPresent()) {
            return header(response, "ETag").flatMap(EntityTag::parse).filter(tag.get()::strongMatches).isPresent();
        }
        var since = parseDate(value);
        var modified = lastModified(response);
        return since.isPresent() && modified.isPresent() && since.get().equals(modified.get());
    }

    private static Optional<Instant> lastModified(Response response) {
        return header(response, "Last-Modified").flatMap(HttpPreconditions::parseDate);
    }

    private static Optional<String> header(Response response, String name) {
        return Optional.ofNullable(response.headers().get(name));
    }

    private static byte[] buffered(Response response) {
        Object body = response.body();
        if (body instanceof byte[] bytes) { return bytes; }
        return body instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : null;
    }

    private static Response bodiless(int status, Response original) {
        var result = Response.of(status, null);
        for (var name : VALIDATORS) {
            var value = original.headers().get(name);
            if (value != null) { result = result.withHeader(name, value); }
        }
        return result;
    }

    private static Response unsatisfiable(Response original, int length) {
        return bodiless(HttpStatus.RANGE_NOT_SATISFIABLE, original)
                .withHeader("Accept-Ranges", "bytes")
                .withHeader("Content-Range", "bytes */" + length);
    }

    private static Response single(Response original, byte[] bytes, ByteRange range) {
        var part = Arrays.copyOfRange(bytes, (int) range.first(), (int) range.last() + 1);
        var result = copyHeaders(Response.of(HttpStatus.PARTIAL_CONTENT, part), original, true);
        return result.withHeader("Content-Range", range.contentRange(bytes.length))
                .withHeader("Accept-Ranges", "bytes");
    }

    private static Response multipart(Response original, byte[] bytes, List<ByteRange> ranges) {
        var out = new ByteArrayOutputStream();
        var contentType = header(original, "Content-Type").orElse("application/octet-stream");
        for (var range : ranges) {
            ascii(out, "--" + BOUNDARY + "\r\n");
            ascii(out, "Content-Type: " + contentType + "\r\n");
            ascii(out, "Content-Range: " + range.contentRange(bytes.length) + "\r\n\r\n");
            out.write(bytes, (int) range.first(), (int) range.length());
            ascii(out, "\r\n");
        }
        ascii(out, "--" + BOUNDARY + "--\r\n");
        var result = copyHeaders(Response.of(HttpStatus.PARTIAL_CONTENT, out.toByteArray()), original, false);
        return result.withHeader("Content-Type", "multipart/byteranges; boundary=" + BOUNDARY)
                .withHeader("Accept-Ranges", "bytes");
    }

    private static Response copyHeaders(Response target, Response original, boolean contentType) {
        for (var entry : original.headers().entrySet()) {
            var name = entry.getKey();
            if (name.equalsIgnoreCase("Content-Length") || !contentType && name.equalsIgnoreCase("Content-Type")) {
                continue;
            }
            target = target.withHeader(name, entry.getValue());
        }
        return target;
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes, 0, bytes.length);
    }

    private static Optional<Instant> parseDate(String value) {
        if (value == null) { return Optional.empty(); }
        Matcher matcher = IMF_DATE.matcher(value);
        if (matcher.matches()) {
            return instant(matcher.group(1), matcher.group(3), matcher.group(2), matcher.group(5),
                    matcher.group(6), matcher.group(7), matcher.group(4), TextStyle.SHORT);
        }
        matcher = OLD_DATE.matcher(value);
        if (matcher.matches()) {
            int twoDigitYear = Integer.parseInt(matcher.group(4));
            int year = twoDigitYear <= 49 ? 2000 + twoDigitYear : 1900 + twoDigitYear;
            return instant(matcher.group(1), matcher.group(3), matcher.group(2), matcher.group(5),
                    matcher.group(6), matcher.group(7), year, TextStyle.FULL);
        }
        matcher = ASC_DATE.matcher(value);
        if (matcher.matches()) {
            return instant(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4),
                    matcher.group(5), matcher.group(6), matcher.group(7), TextStyle.SHORT);
        }
        return Optional.empty();
    }

    private static Optional<Instant> instant(String weekday, String month, String day,
            String hour, String minute, String second, String year, TextStyle style) {
        return instant(weekday, month, day, hour, minute, second, Integer.parseInt(year), style);
    }

    private static Optional<Instant> instant(String weekday, String month, String day,
            String hour, String minute, String second, int year, TextStyle style) {
        int index = MONTHS.indexOf(month);
        if (month.length() != 3 || index < 0 || index % 3 != 0 || index > 33) { return Optional.empty(); }
        int number = index / 3 + 1;
        LocalDateTime date;
        try {
            date = LocalDateTime.of(year, number, Integer.parseInt(day.trim()), Integer.parseInt(hour),
                    Integer.parseInt(minute), Integer.parseInt(second));
        } catch (DateTimeException | NumberFormatException invalid) {
            return Optional.empty();
        }
        if (!date.getDayOfWeek().getDisplayName(style, Locale.US).equals(weekday)) { return Optional.empty(); }
        return Optional.of(date.toInstant(ZoneOffset.UTC));
    }
}
