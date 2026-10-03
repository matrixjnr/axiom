package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestTargetsTest {
    @Test void leavesOriginAndAsteriskFormsAlone() {
        assertThat(RequestTargets.originForm("/a/b?x=1", "h")).isEqualTo("/a/b?x=1");
        assertThat(RequestTargets.originForm("*", "h")).isEqualTo("*");
        assertThat(RequestTargets.originForm("//a", "h")).isEqualTo("//a"); // Left for the path rules to reject.
        assertThat(RequestTargets.originForm("a", "h")).isEqualTo("a");
    }

    @Test void reducesAbsoluteFormToPathAndQuery() {
        assertThat(RequestTargets.originForm("http://h/a/b?x=1", "h")).isEqualTo("/a/b?x=1");
        assertThat(RequestTargets.originForm("HTTPS://H:8443/a", "h:8443")).isEqualTo("/a");
        assertThat(RequestTargets.originForm("http://[::1]:80/a", "[::1]:80")).isEqualTo("/a");
        assertThat(RequestTargets.originForm("http://h", "h")).isEqualTo("/");
        assertThat(RequestTargets.originForm("http://h?x=1", "h")).isEqualTo("/?x=1");
        assertThat(RequestTargets.originForm("http://h/", null)).isEqualTo("/");
        // An embedded absolute URI in the path or query stays part of the path or query.
        assertThat(RequestTargets.originForm("http://h/a?u=http://x/y", "h")).isEqualTo("/a?u=http://x/y");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://h/a", "ws://h/a", "://h/a", "http:///a", "http://u@h/a", "http://u:p@h/a",
            "http://h:99999/a", "http://h:/a", "http://h#f", "http://a b/c", "http://h:80:80/a", "http://%/a"})
    void rejectsInvalidSchemesAndAuthorities(String target) {
        assertThatIllegalArgumentException().isThrownBy(() -> RequestTargets.originForm(target, "h"));
    }

    @Test void rejectsAnAuthorityThatDiffersFromHost() {
        assertThatIllegalArgumentException().isThrownBy(() -> RequestTargets.originForm("http://a/", "b"));
        assertThatIllegalArgumentException().isThrownBy(() -> RequestTargets.originForm("http://a:80/", "a"));
        assertThatIllegalArgumentException().isThrownBy(() -> RequestTargets.originForm("http://a/", "a:80"));
        assertThatIllegalArgumentException().isThrownBy(() -> RequestTargets.originForm("http://a./", "a"));
    }

    @Test void validatesHostAuthorities() {
        assertThat(RequestTargets.validAuthority("example.com")).isTrue();
        assertThat(RequestTargets.validAuthority("example.com:8080")).isTrue();
        assertThat(RequestTargets.validAuthority("[::1]:8080")).isTrue();
        assertThat(RequestTargets.validAuthority("")).isFalse();
        assertThat(RequestTargets.validAuthority("a:")).isFalse();
        assertThat(RequestTargets.validAuthority("a:99999")).isFalse();
        assertThat(RequestTargets.validAuthority("u@a")).isFalse();
        assertThat(RequestTargets.validAuthority("a/b")).isFalse();
    }
}
