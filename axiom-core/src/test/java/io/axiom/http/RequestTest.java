package io.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "users", "/users?x=1", "/users#top", "/bad path", "/bad%", "/bad%zz", "/bad%2",
            "/a\\b", "/a\r\nb", "/a\u0000b", "//", "//users", "/a//b", "/users//", "/.", "/..", "/a/./b",
            "/a/../b", "/a/..", "/%2e%2e/b", "/%2E", "/a.%2e", "/a%2Fb", "/a%2fb", "/a%5Cb", "/a%5cb", "/a%00b"})
    void rejectsUnsafePathsWithADistinctException(String path) {
        assertThatThrownBy(() -> Request.get(path)).isInstanceOf(InvalidRequestPathException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "G ET", "GET\r\n", "GÉT", "GET/"})
    void rejectsInvalidMethods(String method) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Request(method, "/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/users/", "/Users", "/a%20b", "/a%2520b", "/%E2%82%AC", "/é", "/a.b", "/...",
            "/..a", "/.well-known/x", "/time/12:00", "/a+b;c=d@e"})
    void preservesAcceptedRawPathsWithoutDecoding(String path) {
        assertThat(Request.get(path).path()).isEqualTo(path);
    }

    @Test
    void parsesOriginFormTargetsOnceAndDiscardsTheQuery() {
        assertThat(Request.fromTarget("GET", "/a%20b")).isEqualTo(Request.get("/a%20b"));
        assertThat(Request.fromTarget("GET", "/a?x=1&y=%2F/?z")).isEqualTo(Request.get("/a"));
        assertThat(Request.fromTarget("POST", "/a?").path()).isEqualTo("/a");
        for (var invalidQuery : new String[] {"/a?bad%zz", "/a?%2", "/a?x#frag", "/a?x y", "/a?\\"}) {
            assertThatIllegalArgumentException().as(invalidQuery)
                    .isThrownBy(() -> Request.fromTarget("GET", invalidQuery));
        }
        for (var invalidPath : new String[] {"*", "http://host/a", "/a#frag", "/a/../b?x", "//a?x"}) {
            assertThatThrownBy(() -> Request.fromTarget("GET", invalidPath)).as(invalidPath)
                    .isInstanceOf(InvalidRequestPathException.class);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> Request.fromTarget("G ET", "/"));
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
