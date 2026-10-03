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
    void carriesImmutableAttributesAndCountsThemInToString() {
        var source = new java.util.HashMap<>(Map.of("tenant", "acme", "email", "ada@example.com"));
        var identity = new SecurityIdentity("ada", Set.of(), Set.of(), source);
        source.put("tenant", "evil");
        assertThat(identity.attribute("tenant")).contains("acme");
        assertThat(identity.attribute("missing")).isEmpty();
        assertThat(identity.attributes()).containsOnlyKeys("tenant", "email");
        assertThatThrownBy(() -> identity.attributes().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(identity.toString()).contains("attributes=2").doesNotContain("acme", "ada@example.com");
        assertThat(SecurityIdentity.of("ada").attributes()).isEmpty();
        assertThat(new SecurityIdentity("ada", Set.of(), Set.of())).isEqualTo(SecurityIdentity.of("ada"));
        var merged = identity.withAttributes(Map.of("tenant", "globex", "plan", "pro"));
        assertThat(merged.attributes()).containsEntry("tenant", "globex").containsEntry("plan", "pro").containsEntry("email", "ada@example.com");
        assertThat(identity.attribute("tenant")).contains("acme");
    }

    @Test
    void rejectsUnusableAttributes() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityIdentity("ada", Set.of(), Set.of(), Map.of("", "v")));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityIdentity("ada", Set.of(), Set.of(), Map.of("a\nb", "v")));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityIdentity("ada", Set.of(), Set.of(), Map.of("a", "v\r\nx")));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityIdentity("ada", Set.of(), Set.of(), Map.of("a", "x".repeat(2049))));
        new SecurityIdentity("ada", Set.of(), Set.of(), Map.of("empty", "", "max", "x".repeat(2048)));
        var many = new java.util.HashMap<String, String>();
        for (int i = 0; i < 65; i++) { many.put("a" + i, "v"); }
        assertThatIllegalArgumentException().isThrownBy(() -> new SecurityIdentity("ada", Set.of(), Set.of(), many));
        assertThatNullPointerException().isThrownBy(() -> new SecurityIdentity("ada", Set.of(), Set.of(), null));
    }

    @Test
    void contextsWithoutIdentitySupportAreAnonymousAndRefuseIdentities() {
        Context context = new Double();
        assertThat(context.identity()).isEmpty();
        assertThatThrownBy(() -> context.identity(SecurityIdentity.of("ada")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void contextsWithoutRouterSupportRefuseTheAutomaticOptionsAnswer() {
        assertThatThrownBy(() -> new Double().automaticOptions()).isInstanceOf(UnsupportedOperationException.class);
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
