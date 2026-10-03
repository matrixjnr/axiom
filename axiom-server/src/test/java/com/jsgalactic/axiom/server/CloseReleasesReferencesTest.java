package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.ErrorHandler;
import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code close()} drops the application's references to registered handlers, middleware (global,
 * group and route) and error handlers. Each is reachable only through the application, so a weak
 * reference to it clears once the application releases it while the closed application itself
 * stays strongly reachable. There is no sleeping: a bounded number of {@code System.gc()} calls
 * is enough for the JVM to clear weak references to unreachable objects.
 */
class CloseReleasesReferencesTest {
    // Anonymous classes, never non-capturing lambdas: those are shared constants and stay reachable.
    private static Map<String, WeakReference<Object>> register(Application app) {
        var refs = new LinkedHashMap<String, WeakReference<Object>>();
        var global = new Middleware() {
            @Override public Response handle(Context ctx, Next next) throws Exception { return next.run(); }
        };
        var group = new Middleware() {
            @Override public Response handle(Context ctx, Next next) throws Exception { return next.run(); }
        };
        var route = new Middleware() {
            @Override public Response handle(Context ctx, Next next) throws Exception { return next.run(); }
        };
        var handler = new Handler() {
            @Override public Object handle(Context ctx) { return "ok"; }
        };
        var error = new ErrorHandler<IllegalStateException>() {
            @Override public Response handle(Context ctx, IllegalStateException failure) { return null; }
        };
        refs.put("global middleware", new WeakReference<>(global));
        refs.put("group middleware", new WeakReference<>(group));
        refs.put("route middleware", new WeakReference<>(route));
        refs.put("handler", new WeakReference<>(handler));
        refs.put("error handler", new WeakReference<>(error));
        app.use(global);
        app.group("/g", g -> {
            g.use(group);
            g.get("/x", handler, route);
        });
        app.error(IllegalStateException.class, error);
        return refs;
    }

    private static boolean cleared(WeakReference<Object> reference) {
        for (int attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
            System.gc();
            Thread.onSpinWait();
        }
        return reference.get() == null;
    }

    @Test
    void closeReleasesHandlerMiddlewareAndErrorHandlerReferences() throws Exception {
        var app = Axiom.create();
        var refs = register(app);
        app.start();
        assertThat(app.handle(Request.get("/g/x")).status()).isEqualTo(200);

        // While the application is running every registered object is still reachable through it.
        System.gc();
        refs.forEach((name, reference) -> assertThat(reference.get()).as("%s while running", name).isNotNull());

        app.close();

        refs.forEach((name, reference) -> assertThat(cleared(reference)).as("%s after close", name).isTrue());
        // The closed application is still strongly held by this frame: the references were dropped
        // by close(), not collected together with the application.
        assertThat(app.state()).isEqualTo(Application.State.CLOSED);
    }

    @Test
    void closeBeforeStartReleasesRegistrations() {
        var app = Axiom.create();
        var refs = register(app);

        app.close();

        refs.forEach((name, reference) -> assertThat(cleared(reference)).as("%s after close before start", name).isTrue());
        assertThat(app.state()).isEqualTo(Application.State.CLOSED);
    }
}
