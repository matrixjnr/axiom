package io.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "users", "/users?x=1", "/users#top", "/bad path", "/bad%", "/a\\b", "/a\r\nb"})
    void rejectsInvalidPaths(String path) {
        assertThatIllegalArgumentException().isThrownBy(() -> Request.get(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "G ET", "GET\r\n", "GÉT", "GET/"})
    void rejectsInvalidMethods(String method) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Request(method, "/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "//users", "/a/../b", "/users/", "/Users", "/a%2Fb", "/a%2fb"})
    void preservesRawPath(String path) {
        assertThat(Request.get(path).path()).isEqualTo(path);
    }

    @Test
    void preservesMethodCaseAndAllowsExtensionMethods() {
        assertThat(new Request("propfind", "/").method()).isEqualTo("propfind");
        assertThat(new Request("CUSTOM-METHOD", "/").method()).isEqualTo("CUSTOM-METHOD");
    }

    @Test
    void rejectsNullMetadata() {
        assertThatNullPointerException().isThrownBy(() -> new Request(null, "/"));
        assertThatNullPointerException().isThrownBy(() -> Request.get(null));
    }
}
