package com.jsgalactic.axiom.lifecycle;

import java.io.IOException;

/**
 * Thrown when TLS material cannot be used: a file is missing or unreadable, a PEM block is
 * malformed, the key does not belong to the certificate, a certificate is expired or not yet valid,
 * or the configured protocols and cipher suites leave nothing the JVM supports. It is thrown when a
 * listener starts and by {@link Server#reloadTls()}.
 *
 * <p>Messages name the file or the problem, never the content of a key, a certificate or a
 * password, and the exception carries no cause that could hold such content.
 */
public final class TlsConfigurationException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     * @param message what is wrong, without key material
     */
    public TlsConfigurationException(String message) { super(message); }
}
