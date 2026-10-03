package example.openapi;

import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.error.UnauthorizedException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.security.Authenticator;
import com.jsgalactic.axiom.security.Security;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * Guards the documentation routes with HTTP Basic authentication (user {@code docs}). A browser
 * asks for the credentials once and then sends them with every request of the Swagger UI page.
 */
public final class DocsAccess {
    private DocsAccess() { }

    // region protect
    /**
     * Returns middleware that answers 401 unless the request carries the docs credentials.
     *
     * @param password the password, read from configuration, never a literal in source
     * @return middleware for {@code serve(app, path, middleware)} and {@code SwaggerUi.register}
     */
    public static Middleware requirePassword(String password) {
        var expected = ("docs:" + password).getBytes(StandardCharsets.UTF_8);
        Authenticator authenticator = new Authenticator() {
            @Override public Optional<SecurityIdentity> authenticate(Request request) {
                var header = request.headers().get("Authorization");
                if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
                    return Optional.empty();
                }
                try {
                    var sent = Base64.getDecoder().decode(header.substring(6).trim());
                    if (MessageDigest.isEqual(sent, expected)) {
                        return Optional.of(new SecurityIdentity("docs", Set.of("docs"), Set.of()));
                    }
                } catch (IllegalArgumentException notBase64) {
                    // falls through to the same refusal as a wrong password
                }
                throw new UnauthorizedException(challenge(), "invalid_credentials");
            }

            @Override public String challenge() { return "Basic realm=\"docs\", charset=\"UTF-8\""; }
        };
        return Security.of(authenticator).hasRole("docs");
    }
    // endregion protect
}
