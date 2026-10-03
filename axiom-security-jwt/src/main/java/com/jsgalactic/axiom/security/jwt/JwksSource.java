package com.jsgalactic.axiom.security.jwt;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Where {@link JwtAuthenticator} gets an issuer's JSON Web Key Set (RFC 7517) from. The
 * application configures the source; a token can never choose it.
 *
 * <p>Implement it to fetch the document some other way, or in tests to inject a key set without a
 * network:
 * <pre>{@code
 * JwksSource fixed = maxBytes -> jwksJson.getBytes(StandardCharsets.UTF_8);
 * }</pre>
 *
 * <p>{@link #url(URI)} and {@link #file(Path)} are the supplied sources. Both read at most the
 * requested number of bytes and fail with an {@link IOException} otherwise. An implementation is
 * called by one thread at a time per authenticator, may block, and must return within a bounded
 * time.
 */
@FunctionalInterface
public interface JwksSource {
    /**
     * Reads the key set document.
     *
     * @param maxBytes the longest document the caller accepts; an implementation should stop reading
     *        beyond it and fail (a longer result is rejected by the caller anyway)
     * @return the document, UTF-8 JSON
     * @throws IOException if the document cannot be read within the bounds
     */
    byte[] fetch(int maxBytes) throws IOException;

    /**
     * Fetches the key set over HTTPS with a 5 second timeout.
     *
     * @param uri an {@code https} URI, or {@code http} to a loopback address for development
     * @return the source
     * @throws IllegalArgumentException for another scheme, user information, a fragment or no host
     * @see #url(URI, Duration)
     */
    static JwksSource url(URI uri) {
        return url(uri, Duration.ofSeconds(5));
    }

    /**
     * Fetches the key set with {@code java.net.http}. Redirects are never followed, so a response
     * cannot send the client to another host: a 3xx status is a failure. Only status 200 with a
     * {@code application/json} or {@code application/jwk-set+json} content type is accepted. The
     * body is read incrementally and the exchange is abandoned as soon as it exceeds the byte
     * limit or the timeout, which covers connecting, the response head and the whole body. No
     * credentials, cookies or proxy authentication are sent; the connection uses the JVM's
     * default TLS trust and proxy settings.
     *
     * @param uri an {@code https} URI, or {@code http} to a loopback address for development
     * @param timeout 100 milliseconds to 60 seconds for the whole exchange
     * @return the source, which owns one HTTP client for its lifetime
     * @throws IllegalArgumentException for another scheme, user information, a fragment, no host
     *         or a timeout out of range
     */
    static JwksSource url(URI uri, Duration timeout) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("The key set timeout is 100 milliseconds to 60 seconds");
        }
        var scheme = uri.getScheme();
        var host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null || scheme == null) {
            throw new IllegalArgumentException("A key set URL has a host and no user information or fragment");
        }
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new IllegalArgumentException("A key set URL is https (http only to a loopback address)");
        }
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
        return maxBytes -> HttpJwks.get(client, uri, timeout, maxBytes);
    }

    /**
     * Reads the key set from a file, for example one a deployment tool refreshes.
     *
     * @param path a regular file
     * @return the source; the file is read afresh on every fetch
     */
    static JwksSource file(Path path) {
        Objects.requireNonNull(path, "path");
        return maxBytes -> {
            try (InputStream in = Files.newInputStream(path)) {
                var bytes = in.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) { throw new IOException("The key set file is longer than " + maxBytes + " bytes"); }
                return bytes;
            }
        };
    }
}
