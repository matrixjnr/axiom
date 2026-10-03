package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.application.spi.ApplicationProvider;

/** Service-loader entry point; not application API. */
public final class DefaultApplicationProvider implements ApplicationProvider {
    /** Creates the service provider. */
    public DefaultApplicationProvider() {}

    @Override
    public Application create() { return new DefaultApplication(); }
}
