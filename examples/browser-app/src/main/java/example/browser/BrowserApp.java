package example.browser;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.error.UnauthorizedException;
import com.jsgalactic.axiom.security.Csrf;
import com.jsgalactic.axiom.security.InMemorySessionStore;
import com.jsgalactic.axiom.security.RateLimit;
import com.jsgalactic.axiom.security.RateLimitKey;
import com.jsgalactic.axiom.security.Security;
import com.jsgalactic.axiom.security.SecurityHeaders;
import com.jsgalactic.axiom.security.Sessions;
import com.jsgalactic.axiom.security.TrustedProxies;
import java.time.Duration;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * A small notes service for a browser: cookie sessions, CSRF protection and rate limits.
 *
 * <p>The page first calls {@code GET /csrf} and sends the token it receives in the
 * {@code X-CSRF-Token} header of every {@code POST}. {@code POST /login} takes the user in the
 * {@code user} query parameter and the proof in the {@code X-Login-Code} header (a real application
 * verifies a posted form against its user store; the check is injected here).
 */
public final class BrowserApp {
    private BrowserApp() { }

    /**
     * Builds the application.
     *
     * @param credentials decides whether a user and login code belong together
     * @param proxies the reverse proxies in front of the service, or {@link TrustedProxies#none()}
     * @return an application that is not started yet
     */
    public static Application create(BiPredicate<String, String> credentials, TrustedProxies proxies) {
        var sessions = Sessions.builder(InMemorySessionStore.builder()
                .idleTimeout(Duration.ofMinutes(30)).absoluteTimeout(Duration.ofHours(8)).maxSessions(50_000).build()).build();
        var security = Security.of(sessions.authenticator());
        var csrf = Csrf.synchronizer(sessions).build();
        var perClient = RateLimitKey.clientAddress(proxies);
        var general = RateLimit.builder(300, Duration.ofMinutes(1)).key(perClient).name("general").headers(true).build();
        var logins = RateLimit.builder(5, Duration.ofMinutes(1)).key(perClient).name("login").build();

        var app = Axiom.create();
        app.use(SecurityHeaders.defaults());
        app.use(general);                       // every route: 300 requests per minute and client address
        app.use(sessions);                      // before anything that reads the session
        app.use(security.authenticate());       // identity from the session cookie, if any
        app.use(csrf);                          // unsafe methods need the token

        app.get("/csrf", ctx -> csrf.token(ctx));
        app.post("/login", ctx -> {
            var user = ctx.query("user").orElseThrow(() -> new UnauthorizedException("Cookie realm=\"session\""));
            var code = ctx.header("X-Login-Code").orElse("");
            if (!credentials.test(user, code)) { throw new UnauthorizedException("Cookie realm=\"session\"", "invalid_credentials"); }
            sessions.session(ctx).authenticate(new SecurityIdentity(user, Set.of("member"), Set.of()));   // new session identifier
            return ctx.noContent();
        }, logins);                             // 5 attempts per minute and client address
        app.post("/logout", ctx -> {
            sessions.session(ctx).invalidate();
            return ctx.noContent();
        });
        app.get("/me", ctx -> ctx.identity().orElseThrow().principal(), security.authenticated());
        app.post("/notes", ctx -> {
            sessions.session(ctx).attribute("note", ctx.query("text").orElse(""));
            return ctx.noContent();
        }, security.authenticated());
        app.get("/notes", ctx -> sessions.session(ctx).attribute("note").orElse(""), security.authenticated());
        return app;
    }

    /**
     * Serves the application on http://127.0.0.1:8080 with a login code printed at startup.
     *
     * @param args unused
     * @throws Exception if the listener cannot start
     */
    public static void main(String[] args) throws Exception {
        var code = java.util.UUID.randomUUID().toString();
        var app = create((user, supplied) -> user.equals("demo") && java.security.MessageDigest.isEqual(
                code.getBytes(java.nio.charset.StandardCharsets.UTF_8), supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                TrustedProxies.none()).closeOnJvmShutdown();
        var server = app.listen(8080);
        System.out.println("Listening on http://127.0.0.1:" + server.localAddress().getPort() + "/ ; user demo, X-Login-Code " + code);
        server.termination().toCompletableFuture().join();
    }
}
