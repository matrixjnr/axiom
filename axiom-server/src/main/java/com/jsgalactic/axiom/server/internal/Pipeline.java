package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.http.Response;
import java.util.List;

/**
 * Composes middleware chains once at startup. A composed chain is a {@link Handler} that always
 * returns a {@link Response}; per request it allocates only one {@link Link} per middleware.
 */
final class Pipeline {
    private Pipeline() {}

    /**
     * Wraps a handler in middleware, outermost first. The handler's result is mapped to a
     * response with the context's settings before the innermost middleware sees it.
     */
    static Handler compose(List<Middleware> middleware, Handler handler, List<ErrorHandlers> scopes,
                           FailureLog log, boolean development) {
        Handler chain = context -> {
            var result = handler.handle(context);
            return result instanceof Response response ? response : context.response(result);
        };
        for (int i = middleware.size() - 1; i >= 0; i--) {
            chain = wrap(middleware.get(i), chain, i, middleware.size());
        }
        return new ErrorBoundary(chain, middleware.toArray(new Middleware[0]), scopes, log, development);
    }

    /**
     * Wraps one middleware. The context records how many middleware are on the stack, so that an
     * exception leaves behind exactly the ones that were entered when it was thrown: entering sets
     * the count to this middleware's depth, a normal return restores the enclosing depth, and an
     * exception is counted where it first comes into view, either leaving {@code next.run()} (from
     * the inner middleware or the handler) or leaving a middleware that threw it itself. A
     * middleware that catches an exception and throws another one is therefore counted, and the
     * ones it had called are not.
     */
    private static Handler wrap(Middleware middleware, Handler inner, int index, int size) {
        return context -> {
            var state = (DefaultContext) context;
            state.entered(index + 1);
            var next = new Link(inner, state, Math.min(index + 2, size));
            try {
                var response = middleware.handle(context, next);
                if (response == null) {
                    throw new IllegalStateException("Middleware " + middleware.getClass().getName()
                            + " returned null; return a Response, for example next.run()");
                }
                state.entered(index);
                return response;
            } catch (Exception | Error failure) {
                state.observed(failure, index + 1);
                throw failure;
            } finally {
                next.used = true;
            }
        };
    }

    /**
     * The rest of a chain for one request. Confined to the request's thread like the context: a
     * call from any other thread fails before reading {@code used}, so the flag needs no
     * synchronization.
     */
    private static final class Link implements Middleware.Next {
        private final Handler inner;
        private final DefaultContext context;
        private final int depth;
        private final Thread owner = Thread.currentThread();
        private boolean used;

        Link(Handler inner, DefaultContext context, int depth) {
            this.inner = inner;
            this.context = context;
            this.depth = depth;
        }

        @Override
        public Response run() throws Exception {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("next.run() must be called on the request's thread");
            }
            if (used) {
                throw new IllegalStateException("next.run() may be called once, and only while the middleware runs");
            }
            used = true;
            try {
                return (Response) inner.handle(context);
            } catch (Exception | Error failure) {
                context.observed(failure, depth);
                throw failure;
            }
        }
    }
}
