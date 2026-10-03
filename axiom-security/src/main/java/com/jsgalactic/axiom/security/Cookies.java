package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.http.Request;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The cookies of a request, parsed strictly from the {@code Cookie} header (RFC 6265bis section 4.2).
 *
 * <p>Accepted: {@code name=value} pairs separated by exactly {@code "; "}. A name is a non-empty
 * HTTP token; a value consists of cookie octets only (visible ASCII without space, double quote,
 * comma, semicolon or backslash) and may be empty. Anything else (quoted values, stray whitespace,
 * a pair without {@code =}, a nameless cookie, control or non-ASCII characters, more than
 * {@value #MAX_PAIRS} pairs, a header longer than the limit) makes the whole header invalid:
 * {@link #parse} throws, and {@link #of(Request)} answers "no cookies", so security decisions fail
 * closed instead of reading a half-understood header.
 *
 * <p>A name that appears twice is ambiguous (a sibling sub-domain can plant a second cookie of the
 * same name), so {@link #get} treats it as absent; {@link #all} shows every value.
 *
 * <p>Immutable and thread-safe. Values are credentials when they are session identifiers or
 * tokens: {@code toString()} lists only the names.
 */
public final class Cookies {
    /** Default longest {@code Cookie} header accepted, in characters. */
    public static final int DEFAULT_MAX_LENGTH = 8192;
    /** Most cookie pairs accepted in one header. */
    public static final int MAX_PAIRS = 128;

    private static final Cookies EMPTY = new Cookies(Map.of());

    private final Map<String, List<String>> cookies;

    private Cookies(Map<String, List<String>> cookies) {
        this.cookies = cookies;
    }

    /**
     * Returns no cookies.
     *
     * @return an empty set
     */
    public static Cookies none() {
        return EMPTY;
    }

    /**
     * Reads the request's cookies; a missing, oversized or malformed header yields none.
     *
     * @param request the request
     * @return the cookies, empty when the header is absent or invalid
     */
    public static Cookies of(Request request) {
        return of(request, DEFAULT_MAX_LENGTH);
    }

    /**
     * Reads the request's cookies with a header size limit; a missing, oversized or malformed
     * header yields none.
     *
     * @param request the request
     * @param maxLength longest accepted header, 64 to 65,536 characters
     * @return the cookies, empty when the header is absent or invalid
     */
    public static Cookies of(Request request, int maxLength) {
        var header = Objects.requireNonNull(request, "request").header("Cookie");
        if (header.isEmpty()) { return EMPTY; }
        try {
            return parse(header.get(), maxLength);
        } catch (IllegalArgumentException malformed) {
            return EMPTY;
        }
    }

    /**
     * Parses a {@code Cookie} header value strictly with the default size limit.
     *
     * @param header the header value
     * @return the cookies
     * @throws IllegalArgumentException if the header is malformed or too long
     */
    public static Cookies parse(String header) {
        return parse(header, DEFAULT_MAX_LENGTH);
    }

    /**
     * Parses a {@code Cookie} header value strictly.
     *
     * @param header the header value
     * @param maxLength longest accepted header, 64 to 65,536 characters
     * @return the cookies
     * @throws IllegalArgumentException if the header is malformed or longer than {@code maxLength}
     */
    public static Cookies parse(String header, int maxLength) {
        Objects.requireNonNull(header, "header");
        if (maxLength < 64 || maxLength > 65_536) { throw new IllegalArgumentException("maxLength must be between 64 and 65,536"); }
        if (header.length() > maxLength) { throw new IllegalArgumentException("Cookie header too long"); }
        var parsed = new LinkedHashMap<String, List<String>>();
        int pairs = 0;
        int start = 0;
        while (true) {
            int end = header.indexOf(';', start);
            var pair = header.substring(start, end < 0 ? header.length() : end);
            if (++pairs > MAX_PAIRS) { throw new IllegalArgumentException("Too many cookies"); }
            int equals = pair.indexOf('=');
            if (equals < 1) { throw new IllegalArgumentException("Malformed cookie pair"); }
            var name = pair.substring(0, equals);
            var value = pair.substring(equals + 1);
            if (!isToken(name) || !isCookieValue(value)) { throw new IllegalArgumentException("Malformed cookie pair"); }
            parsed.computeIfAbsent(name, key -> new ArrayList<>(1)).add(value);
            if (end < 0) { break; }
            if (end + 1 >= header.length() || header.charAt(end + 1) != ' ') { throw new IllegalArgumentException("Cookie pairs are separated by \"; \""); }
            start = end + 2;
        }
        parsed.replaceAll((name, values) -> List.copyOf(values));
        return new Cookies(Collections.unmodifiableMap(parsed));
    }

    /**
     * Returns the value of the cookie, if it was sent exactly once.
     *
     * @param name cookie name, case-sensitive
     * @return the value, or empty when absent or sent more than once
     */
    public Optional<String> get(String name) {
        var values = cookies.get(Objects.requireNonNull(name, "name"));
        return values != null && values.size() == 1 ? Optional.of(values.get(0)) : Optional.empty();
    }

    /**
     * Returns every value sent for the name, in header order.
     *
     * @param name cookie name, case-sensitive
     * @return the values, possibly none
     */
    public List<String> all(String name) {
        return cookies.getOrDefault(Objects.requireNonNull(name, "name"), List.of());
    }

    /**
     * Returns the distinct cookie names.
     *
     * @return the names in header order
     */
    public java.util.Set<String> names() {
        return cookies.keySet();
    }

    @Override
    public String toString() {
        return "Cookies" + cookies.keySet();
    }

    static boolean isToken(String text) {
        if (text.isEmpty()) { return false; }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = c > 0x20 && c < 0x7f && "()<>@,;:\\\"/[]?={}".indexOf(c) < 0;
            if (!ok) { return false; }
        }
        return true;
    }

    static boolean isCookieValue(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = c > 0x20 && c < 0x7f && c != '"' && c != ',' && c != ';' && c != '\\';
            if (!ok) { return false; }
        }
        return true;
    }
}
