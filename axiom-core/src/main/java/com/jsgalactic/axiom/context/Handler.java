package com.jsgalactic.axiom.context;

/** An ordinary Java callback, invoked once per matched request. */
@FunctionalInterface
public interface Handler {
    /**
     * Handles one matched request.
     *
     * @param context the request-scoped context
     * @return a Response, a body value, or null for no content
     * @throws Exception if application processing fails
     */
    Object handle(Context context) throws Exception;
}
