package com.jsgalactic.axiom.security;

/**
 * The family of proxy headers {@link TrustedProxies} believes. Exactly one family is read, never
 * both, so a request cannot mix a spoofed header of one family with a genuine header of the other
 * and make the result ambiguous. The other family is ignored entirely.
 */
public enum ForwardedHeaders {
    /** {@code X-Forwarded-For}, {@code X-Forwarded-Proto}, {@code X-Forwarded-Host}, {@code X-Forwarded-Port}. */
    X_FORWARDED,
    /** The standard RFC 7239 {@code Forwarded} header ({@code for}, {@code proto}, {@code host}). */
    FORWARDED
}
