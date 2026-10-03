package com.jsgalactic.axiom.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.routing.Route;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityIdentityTest {
    @Test
    void copiesGrantsSoTheIdentityCannotChangeAfterCreation() {
        var roles = new HashSet<>(Set.of("reader"));
        var identity = new SecurityIdentity("ada", roles, Set.of("notes:read"));
        roles.add("admin");
        assertThat(identity.hasRole("reader")).isTrue();
        assertThat(identity.hasRole("admin")).isFalse();
        assertThat(identity.hasPermission("notes:read")).isTrue();
        assertThatThrownBy(() -> identity.roles().add("admin")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void comparesGrantsExactly() {
        var identity = new SecurityIdentity("ada", Set.of("Admin"), Set.of());
        assertThat(identity.hasRole("admin")).isFalse();
        assertThat(SecurityIdentity.of("ada").roles()).isEmpty();
    }

    @Test
    void rejectsUnusableNames() {
        assertThatIllegalArgumentException().isThrownBy(() -> SecurityIdentity.of(""));
        assertThatIllegalArgumentException().isThrownBy(() -> SecurityIdentity.of("a\r\nb"));
        assertThatIllegalArgumentException().isThrownBy(() -> SecurityIdentity.of("x".repeat(257)));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityIdentity("ada", Set.of(""), Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityIdentity("ada", Set.of(), Set.of("\u0000")));
        assertThatNullPointerException().isThrownBy(() -> SecurityIdentity.of(null));
        var withNull = new HashSet<String>();
        withNull.add(null);
        assertThatNullPointerException().isThrownBy(() -> new SecurityIdentity("ada", withNull, Set.of()));
    }

    @Test
    void describesItselfWithoutListingGrants() {
        var identity = new SecurityIdentity("ada", Set.of("secret-role"), Set.of("p1", "p2"));
        assertThat(identity.toString()).contains("ada", "roles=1", "permissions=2").doesNotContain("secret-role");
    }

    @Test
    void contextsWithoutIdentitySupportAreAnonymousAndRefuseIdentities() {
        Context context = new Double();
        assertThat(context.identity()).isEmpty();
        assertThatThrownBy(() -> context.identity(SecurityIdentity.of("ada")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** An application test double that does not override the identity methods. */
    private record Double() implements Context {
        @Override public Request request() { return Request.get("/"); }
        @Override public ExecutionContext execution() { return ExecutionContext.create(Duration.ofSeconds(1)); }
        @Override public <T> T body(Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public Context status(int status) { return this; }
        @Override public Route route() { return new Route("GET", "/"); }
        @Override public String path(String name) { throw new IllegalArgumentException(name); }
        @Override public Map<String, String> pathParameters() { return Map.of(); }
        @Override public Response response(Object body) { return Response.of(200, body); }
    }
}
