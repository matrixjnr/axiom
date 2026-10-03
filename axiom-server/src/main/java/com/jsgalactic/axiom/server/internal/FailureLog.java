package com.jsgalactic.axiom.server.internal;

import java.util.Objects;

/** Where and at which level the runtime logs failures that it answered; immutable. */
record FailureLog(System.Logger logger, System.Logger.Level level) {
    /** The documented default logger name. */
    static final String DEFAULT_LOGGER = "com.jsgalactic.axiom.failures";
    static final FailureLog DEFAULT = new FailureLog(System.getLogger(DEFAULT_LOGGER), System.Logger.Level.WARNING);

    FailureLog {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(level, "level");
        if (level == System.Logger.Level.ALL) {
            throw new IllegalArgumentException("ALL is a threshold, not a level for entries; use e.g. WARNING");
        }
    }

    /** Logs a failure at the configured level, unless that is OFF. */
    void failure(String message, Throwable failure) {
        if (level != System.Logger.Level.OFF) { logger.log(level, message, failure); }
    }

    /** Logs a defect of the application's own error handling; only the destination is configurable. */
    void defect(String message, Throwable failure) {
        logger.log(System.Logger.Level.ERROR, message, failure);
    }
}
