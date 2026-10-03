package example.openapi;

import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.error.UnauthorizedException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.security.Authenticator;
import com.jsgalactic.axiom.security.Security;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;

/** Guards the documentation routes with a shared key sent in the {@code X-Docs-Key} header. */
public final class DocsAccess {
    private DocsAccess() { }

    // region protect
    /**
     * Returns middleware that answers 401 unless the request carries the given key.
     *
     * @param expectedKey the key, read from configuration, never a literal in source
     * @return middleware for {@code serve(app, path, middleware)}
     */
    public static Middleware requireKey(String expectedKey) {
        var expected = expectedKey.getBytes(StandardCharsets.UTF_8);
        Authenticator authenticator = new Authenticator() {
            @Override public Optional<SecurityIdentity> authenticate(Request request) {
                var sent = request.headers().get("X-Docs-Key");
                if (sent == null) {
                    return Optional.empty();
                }
                if (!MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), expected)) {
                    throw new UnauthorizedException(challenge(), "invalid_docs_key");
                }
                return Optional.of(new SecurityIdentity("docs", Set.of("docs"), Set.of()));
            }

            @Override public String challenge() { return "X-Docs-Key realm=\"docs\""; }
        };
        return Security.of(authenticator).hasRole("docs");
    }
    // endregion protect
}
