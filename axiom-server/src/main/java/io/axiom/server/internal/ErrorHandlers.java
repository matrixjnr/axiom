package io.axiom.server.internal;

import io.axiom.context.ErrorHandler;
import io.axiom.error.AxiomException;
import java.util.Map;

/** Immutable exception-class index; the nearest registered superclass wins. */
final class ErrorHandlers {
    static final ErrorHandlers NONE = new ErrorHandlers(Map.of());
    private final Map<Class<?>, ErrorHandler<?>> byType;

    ErrorHandlers(Map<Class<?>, ErrorHandler<?>> byType) { this.byType = Map.copyOf(byType); }

    /**
     * Finds the handler for an exception class, or null when none applies. The built-in problem
     * mapping counts as registered for {@link AxiomException}, so the search stops there.
     */
    @SuppressWarnings("unchecked") // Registration accepts only handlers for the class or a supertype.
    ErrorHandler<Exception> find(Class<?> type) {
        if (byType.isEmpty()) { return null; }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            var handler = byType.get(current);
            if (handler != null) { return (ErrorHandler<Exception>) handler; }
            if (current == AxiomException.class) { return null; }
        }
        return null;
    }
}
