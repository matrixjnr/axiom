package com.jsgalactic.axiom.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict parsing of the RFC 7239 {@code Forwarded} header and of host and port values. */
final class Forwarded {
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern OBFUSCATED = Pattern.compile("_[A-Za-z0-9._-]{1,62}");

    private Forwarded() {}

    /** A validated host with an optional port (0 when absent). */
    record Authority(String host, int port) {}

    /**
     * Splits the header into its elements, left to right. An element that is malformed (a bad
     * parameter, a repeated parameter name) is {@code null}, so the right-to-left walk stops at it.
     * Parameter names are lower case in the result; values are unquoted.
     *
     * @return the elements, or null when the header as a whole is malformed (an unterminated quote)
     */
    static List<Map<String, String>> parse(String header) {
        var parts = split(header, ',');
        if (parts == null) { return null; }
        var elements = new ArrayList<Map<String, String>>(parts.size());
        for (var part : parts) { elements.add(element(part.strip())); }
        return elements;
    }

    private static Map<String, String> element(String text) {
        if (text.isEmpty()) { return null; }
        var pairs = split(text, ';');
        if (pairs == null) { return null; }
        var result = new HashMap<String, String>();
        for (var pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals < 1) { return null; }
            var name = pair.substring(0, equals);
            if (!token(name)) { return null; }
            var value = value(pair.substring(equals + 1));
            if (value == null || result.putIfAbsent(name.toLowerCase(Locale.ROOT), value) != null) { return null; }
        }
        return result;
    }

    /** Splits at a separator outside quoted strings; null if a quote is unterminated. */
    private static List<String> split(String text, char separator) {
        var parts = new ArrayList<String>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '\\') { i++; } else if (c == '"') { quoted = false; }
            } else if (c == '"') {
                quoted = true;
            } else if (c == separator) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (quoted) { return null; }
        parts.add(text.substring(start));
        return Collections.unmodifiableList(parts);
    }

    /** A token or a quoted string, unquoted; null when it is neither. */
    private static String value(String text) {
        if (text.isEmpty()) { return null; }
        if (text.charAt(0) != '"') { return token(text) ? text : null; }
        if (text.length() < 2 || text.charAt(text.length() - 1) != '"') { return null; }
        var out = new StringBuilder();
        for (int i = 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (++i >= text.length() - 1) { return null; }
                c = text.charAt(i);
            } else if (c == '"') {
                return null;
            }
            if ((c < 32 && c != '\t') || c >= 127) { return null; }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean token(String text) {
        if (text.isEmpty()) { return false; }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= ' ' || c >= 127 || "\"(),/:;<=>?@[\\]{}".indexOf(c) >= 0) { return false; }
        }
        return true;
    }

    /**
     * Parses a {@code for} node: an IPv4 literal or a bracketed IPv6 literal with an optional port
     * or obfuscated port. Obfuscated identifiers, {@code unknown} and names yield null.
     */
    static InetAddress node(String text) {
        if (text == null) { return null; }
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) { return null; }
            String port = null;
            if (close + 1 < text.length()) {
                if (text.charAt(close + 1) != ':') { return null; }
                port = text.substring(close + 2);
            }
            var inner = text.substring(1, close);
            var parsed = inner.indexOf(':') < 0 ? null : TrustedProxies.literal(inner);
            return parsed instanceof Inet6Address && nodePort(port) ? parsed : null;
        }
        int colon = text.indexOf(':');
        if (colon != text.lastIndexOf(':')) { return null; } // Bare IPv6 must be bracketed.
        var address = colon < 0 ? text : text.substring(0, colon);
        if (!nodePort(colon < 0 ? null : text.substring(colon + 1)) || address.indexOf('.') < 0) { return null; }
        return TrustedProxies.literal(address);
    }

    private static boolean nodePort(String port) {
        return port == null || OBFUSCATED.matcher(port).matches() || port(port) > 0;
    }

    /** Parses a decimal port of 1 to 65535 without sign or leading zeros; 0 when invalid. */
    static int port(String text) {
        if (text.isEmpty() || text.length() > 5 || text.charAt(0) == '0') { return 0; }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') { return 0; }
        }
        int value = Integer.parseInt(text);
        return value <= 65535 ? value : 0;
    }

    /**
     * Validates a {@code Host}-style value: a DNS name, an IPv4 literal or a bracketed IPv6
     * literal, with an optional port. The host is returned in lower case.
     *
     * @return the authority, or null if the value is not acceptable
     */
    static Authority authority(String text) {
        if (text == null || text.isEmpty() || text.length() > 270) { return null; }
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) { return null; }
            var inner = text.substring(1, close);
            if (inner.indexOf(':') < 0 || !(TrustedProxies.literal(inner) instanceof Inet6Address)) { return null; }
            int port = 0;
            if (close + 1 < text.length()) {
                if (text.charAt(close + 1) != ':') { return null; }
                port = port(text.substring(close + 2));
                if (port == 0) { return null; }
            }
            return new Authority("[" + inner.toLowerCase(Locale.ROOT) + "]", port);
        }
        int colon = text.indexOf(':');
        int port = 0;
        if (colon >= 0) {
            if (colon != text.lastIndexOf(':')) { return null; }
            port = port(text.substring(colon + 1));
            if (port == 0) { return null; }
            text = text.substring(0, colon);
        }
        var host = text.toLowerCase(Locale.ROOT);
        return dnsOrIpv4(host) ? new Authority(host, port) : null;
    }

    private static boolean dnsOrIpv4(String host) {
        if (host.isEmpty() || host.length() > 253) { return false; }
        var labels = host.split("\\.", -1);
        for (var label : labels) {
            if (!LABEL.matcher(label).matches()) { return false; }
        }
        boolean numeric = labels[labels.length - 1].chars().allMatch(c -> c >= '0' && c <= '9');
        return !numeric || TrustedProxies.literal(host) != null; // A numeric last label means an IPv4 literal.
    }
}
