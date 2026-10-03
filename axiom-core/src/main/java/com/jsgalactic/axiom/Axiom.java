package com.jsgalactic.axiom;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.application.spi.ApplicationProvider;
import java.util.ServiceLoader;

/** Creates applications using the runtime available on the application classpath. */
public final class Axiom {
    private Axiom() {}

    /**
     * Creates an independent application in the configuring state.
     *
     * @return a new application
     * @throws IllegalStateException if zero or multiple runtime providers are installed
     */
    public static Application create() {
        var providers = ServiceLoader.load(ApplicationProvider.class).stream().limit(2).toList();
        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "No Axiom runtime provider found. Add axiom-server to the runtime classpath.");
        }
        if (providers.size() != 1) {
            throw new IllegalStateException("Multiple Axiom runtime providers found; install exactly one.");
        }
        return java.util.Objects.requireNonNull(providers.getFirst().get().create(),
                "Application provider returned null");
    }
}
