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
     * Reports whether an Accept header admits a media type. Absent means anything is acceptable;
     * ranges with {@code q=0} and malformed entries admit nothing.
     */
    static boolean acceptable(String accept, String mediaType) {
        if (accept == null) { return true; }
        int slash = mediaType.indexOf('/');
        for (var range : accept.split(",")) {
            var parts = range.split(";");
            var type = parts[0].trim().toLowerCase(Locale.ROOT);
            if (!rejected(parts) && (type.equals("*/*") || type.equals(mediaType)
                    || (type.endsWith("/*") && type.regionMatches(0, mediaType, 0, slash + 1) && type.length() == slash + 2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean rejected(String[] parameters) {
        for (int i = 1; i < parameters.length; i++) {
            var parameter = parameters[i].trim();
            if (parameter.length() >= 2 && parameter.substring(0, 2).equalsIgnoreCase("q=")) {
                var weight = parameter.substring(2).trim();
                return !QUALITY.matcher(weight).matches() || weight.replace("0", "").replace(".", "").isEmpty();
            }
        }
        return false;
    }
}
