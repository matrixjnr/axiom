package io.axiom.server.internal;

import io.axiom.application.Application;
import io.axiom.application.spi.ApplicationProvider;

/** Service-loader entry point; not application API. */
public final class DefaultApplicationProvider implements ApplicationProvider {
    /** Creates the service provider. */
    public DefaultApplicationProvider() {}

    @Override
    public Application create() { return new DefaultApplication(); }
}
