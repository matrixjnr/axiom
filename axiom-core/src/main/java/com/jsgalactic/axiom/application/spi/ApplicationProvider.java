package com.jsgalactic.axiom.application.spi;

import com.jsgalactic.axiom.application.Application;

/**
 * Bootstrap contract implemented by the runtime and discovered through {@link java.util.ServiceLoader}.
 * This SPI is experimental; applications should use {@link com.jsgalactic.axiom.Axiom#create()}.
 */
public interface ApplicationProvider {
    /**
     * Returns a new, independent application.
     *
     * @return a new, independent application
     */
    Application create();
}
