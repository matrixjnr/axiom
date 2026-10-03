package io.axiom.server.internal;

import io.axiom.context.Context;
import io.axiom.context.Handler;
import io.axiom.context.Middleware;
import io.axiom.http.Response;
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
    static Handler compose(List<Middleware> middleware, Handler handler) {
        Handler chain = context -> {
            var result = handler.handle(context);
            return result instanceof Response response ? response : context.response(result);
        };
        for (int i = middleware.size() - 1; i >= 0; i--) {
            chain = wrap(middleware.get(i), chain);
        }
        return chain;
    }

    private static Handler wrap(Middleware middleware, Handler inner) {
        return context -> {
            var next = new Link(inner, context);
            try {
                var response = middleware.handle(context, next);
                if (response == null) {
                    throw new IllegalStateException("Middleware " + middleware.getClass().getName()
                            + " returned null; return a Response, for example next.run()");
                }
                return response;
            } finally {
                next.used = true;
            }
        };
    }

    /** The rest of a chain for one request; confined to the request's thread like the context. */
    private static final class Link implements Middleware.Next {
        private final Handler inner;
        private final Context context;
        private boolean used;

        Link(Handler inner, Context context) {
            this.inner = inner;
            this.context = context;
        }

        @Override
        public Response run() throws Exception {
            if (used) {
                throw new IllegalStateException("next.run() may be called once, and only while the middleware runs");
            }
            used = true;
            return (Response) inner.handle(context);
        }
    }
}
