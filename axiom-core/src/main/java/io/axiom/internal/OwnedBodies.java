package io.axiom.internal;

import io.axiom.http.Body;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Creates bodies that adopt an array without copying it. Transports use this after filling a
 * private array from the network, so a request body is copied exactly once.
 * <p>
 * Not application API: the caller transfers ownership and must never read or write the array
 * again, or the body's immutability guarantee is broken. Applications use {@link Body#of}.
 */
public final class OwnedBodies {
    private static BiFunction<String, byte[], Body> factory;

    private OwnedBodies() {}

    /**
     * Installs the adopting factory; called once by {@link Body}'s static initializer.
     *
     * @param adopting factory that wraps the array without copying
     * @throws IllegalStateException if a factory is already installed
     */
    public static synchronized void install(BiFunction<String, byte[], Body> adopting) {
        if (factory != null) { throw new IllegalStateException("Body factory already installed"); }
        factory = Objects.requireNonNull(adopting, "adopting");
    }

    /**
     * Wraps an array the caller owns and gives up.
     *
     * @param contentType Content-Type header value, or null
     * @param bytes array to adopt; must not be used by the caller afterwards
     * @return body backed by the array
     */
    public static Body adopt(String contentType, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        Body.empty(); // Initializes Body, which installs the factory.
        BiFunction<String, byte[], Body> adopting;
        synchronized (OwnedBodies.class) { adopting = factory; }
        return adopting.apply(contentType, bytes);
    }
}
