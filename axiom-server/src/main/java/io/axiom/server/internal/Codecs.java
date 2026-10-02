package io.axiom.server.internal;

import io.axiom.codec.spi.BodyCodec;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/** Codec discovery and content negotiation over an immutable media-type index. */
final class Codecs {
    private static final Pattern MEDIA_TYPE =
            Pattern.compile("[!#$%&'*+.^_`|~0-9a-z-]+/[!#$%&'*+.^_`|~0-9a-z-]+");
    private static final String TOKEN_CHARACTERS =
            "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern QUALITY = Pattern.compile("0(\\.[0-9]{0,3})?|1(\\.0{0,3})?");
    private final Map<String, BodyCodec> byMediaType;

    private Codecs(Map<String, BodyCodec> byMediaType) { this.byMediaType = Map.copyOf(byMediaType); }

    /**
     * Loads codecs with the thread context class loader, as {@code Axiom.create()} loads the runtime.
     *
     * @throws IllegalStateException if a codec declares an invalid media type or two codecs
     *         declare the same one
     */
    static Codecs discover() {
        return of(ServiceLoader.load(BodyCodec.class));
    }

    static Codecs of(Iterable<BodyCodec> codecs) {
        var index = new HashMap<String, BodyCodec>();
        for (var codec : codecs) {
            for (var type : codec.mediaTypes()) {
                if (!MEDIA_TYPE.matcher(type).matches()) {
                    throw new IllegalStateException("Codec " + codec.getClass().getName()
                            + " declares an invalid media type; use lowercase type/subtype");
                }
                var previous = index.putIfAbsent(type, codec);
                if (previous != null) {
                    throw new IllegalStateException("Codecs " + previous.getClass().getName() + " and "
                            + codec.getClass().getName() + " both handle " + type + "; install only one");
                }
            }
        }
        return new Codecs(index);
    }

    BodyCodec forMediaType(String mediaType) { return byMediaType.get(mediaType); }

    /** Lowercase {@code type/subtype} of a Content-Type value, or null when absent or malformed. */
    static String mediaType(String contentType) {
        if (contentType == null) { return null; }
        int end = contentType.indexOf(';');
        var type = (end < 0 ? contentType : contentType.substring(0, end)).trim().toLowerCase(Locale.ROOT);
        return MEDIA_TYPE.matcher(type).matches() ? type : null;
    }

    /**
     * Reports whether an Accept header admits a media type, following RFC 9110 section 12.5.1.
     * The most specific matching range decides (type/subtype with parameters, then
     * type/subtype, then type/*, then *&#47;*); among equally specific ranges the first listed
     * wins. Its weight must be above zero. The candidate representations carry no media type
     * parameters other than an implied UTF-8 charset, so a range with parameters matches only
     * when every parameter is {@code charset=utf-8}. An absent, blank or malformed header admits
     * everything, as if it were not sent.
     */
    static boolean acceptable(String accept, String mediaType) {
        if (accept == null || accept.isBlank()) { return true; }
        var ranges = parseAccept(accept);
        if (ranges == null) { return true; }
        int slash = mediaType.indexOf('/');
        var type = mediaType.substring(0, slash);
        var subtype = mediaType.substring(slash + 1);
        AcceptRange best = null;
        for (var range : ranges) {
            int specificity = range.specificity(type, subtype);
            if (specificity >= 0 && (best == null || specificity > best.specificity(type, subtype))) { best = range; }
        }
        return best != null && best.weight() > 0;
    }

    /** One parsed media range; {@code utf8Only} is false when it has parameters other than charset=utf-8. */
    record AcceptRange(String type, String subtype, boolean hasParameters, boolean utf8Only, int weight) {
        /** Returns -1 for no match, otherwise 0 (*&#47;*) to 3 (type/subtype with parameters). */
        int specificity(String candidateType, String candidateSubtype) {
            if (type.equals("*")) { return hasParameters ? -1 : 0; }
            if (!type.equals(candidateType)) { return -1; }
            if (subtype.equals("*")) { return hasParameters ? -1 : 1; }
            if (!subtype.equals(candidateSubtype)) { return -1; }
            if (!hasParameters) { return 2; }
            return utf8Only ? 3 : -1;
        }
    }

    /** Parses an Accept field value; returns null when any element is malformed. Weights are in thousandths. */
    static java.util.List<AcceptRange> parseAccept(String value) {
        var ranges = new java.util.ArrayList<AcceptRange>();
        int i = 0;
        int length = value.length();
        while (i <= length) {
            i = skipWhitespace(value, i);
            if (i < length && value.charAt(i) == ',') { i++; continue; } // Empty list elements are allowed.
            if (i >= length) { break; }
            int typeEnd = token(value, i);
            if (typeEnd == i || typeEnd >= length || value.charAt(typeEnd) != '/') { return null; }
            int subtypeEnd = token(value, typeEnd + 1);
            if (subtypeEnd == typeEnd + 1) { return null; }
            var type = value.substring(i, typeEnd).toLowerCase(Locale.ROOT);
            var subtype = value.substring(typeEnd + 1, subtypeEnd).toLowerCase(Locale.ROOT);
            if (type.equals("*") && !subtype.equals("*")) { return null; }
            i = subtypeEnd;
            boolean hasParameters = false;
            boolean utf8Only = true;
            int weight = 1000;
            boolean weighted = false;
            while (true) {
                i = skipWhitespace(value, i);
                if (i >= length || value.charAt(i) == ',') { break; }
                if (value.charAt(i) != ';') { return null; }
                i = skipWhitespace(value, i + 1);
                int nameEnd = token(value, i);
                if (nameEnd == i || nameEnd >= length || value.charAt(nameEnd) != '=') { return null; }
                var name = value.substring(i, nameEnd).toLowerCase(Locale.ROOT);
                i = nameEnd + 1;
                String parameter;
                if (i < length && value.charAt(i) == '"') {
                    var quoted = new StringBuilder();
                    i++;
                    while (true) {
                        if (i >= length) { return null; }
                        char c = value.charAt(i++);
                        if (c == '"') { break; }
                        if (c == '\\') {
                            if (i >= length) { return null; }
                            c = value.charAt(i++);
                        }
                        if (c < 0x20 && c != '\t' || c == 0x7f) { return null; }
                        quoted.append(c);
                    }
                    parameter = quoted.toString();
                } else {
                    int end = token(value, i);
                    if (end == i) { return null; }
                    parameter = value.substring(i, end);
                    i = end;
                }
                if (weighted) { continue; } // Accept extensions after the weight are ignored.
                if (name.equals("q")) {
                    if (!QUALITY.matcher(parameter).matches()) { return null; }
                    weight = (int) Math.round(Double.parseDouble(parameter) * 1000);
                    weighted = true;
                } else {
                    hasParameters = true;
                    utf8Only &= name.equals("charset") && parameter.equalsIgnoreCase("utf-8");
                }
            }
            ranges.add(new AcceptRange(type, subtype, hasParameters, utf8Only, weight));
            if (ranges.size() > 64) { return null; } // Bounds work per request; treated as absent.
        }
        return ranges;
    }

    private static int skipWhitespace(String value, int i) {
        while (i < value.length() && (value.charAt(i) == ' ' || value.charAt(i) == '\t')) { i++; }
        return i;
    }

    private static int token(String value, int i) {
        while (i < value.length() && TOKEN_CHARACTERS.indexOf(value.charAt(i)) >= 0) { i++; }
        return i;
    }
}
