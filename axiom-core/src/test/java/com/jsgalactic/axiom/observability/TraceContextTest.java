package com.jsgalactic.axiom.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.HashSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TraceContextTest {
    private static final String VALID = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Test void parsesTheSpecificationExample() {
        var context = TraceContext.parse(VALID).orElseThrow();
        assertThat(context.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(context.parentId()).isEqualTo("b7ad6b7169203331");
        assertThat(context.flags()).isEqualTo(1);
        assertThat(context.sampled()).isTrue();
        assertThat(context.traceparent()).isEqualTo(VALID);
    }

    @Test void unsampledAndUnknownFlagBitsRoundTrip() {
        var unsampled = TraceContext.parse("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00").orElseThrow();
        assertThat(unsampled.sampled()).isFalse();
        var other = TraceContext.parse("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-fe").orElseThrow();
        assertThat(other.sampled()).isFalse();
        assertThat(other.traceparent()).endsWith("-fe");
    }

    static Stream<String> malformed() {
        return Stream.of(
                "", " ", "garbage",
                " " + VALID, VALID + " ", VALID + "\n", VALID + "-extra", VALID.substring(0, 54),
                "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",          // unsupported version
                "ff-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",          // forbidden version
                "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01",          // uppercase
                "00-0af7651916cd43dd8448eb211c80319c-B7AD6B7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0A",
                "00-00000000000000000000000000000000-b7ad6b7169203331-01",          // zero trace id
                "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01",          // zero parent id
                "00-0af7651916cd43dd8448eb211c80319g-b7ad6b7169203331-01",          // non-hex
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b716920333g-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0g",
                "00_0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",          // wrong separators
                "00-0af7651916cd43dd8448eb211c80319c_b7ad6b7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331_01",
                "00-0af7651916cd43dd8448eb211c80319-b7ad6b7169203331-001",          // shifted field lengths
                VALID + ", " + VALID,                                                // joined duplicate headers
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-+1",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-١١");
    }

    @ParameterizedTest @MethodSource("malformed") void ignoresEverythingThatIsNotStrictlyValid(String header) {
        assertThat(TraceContext.parse(header)).isEmpty();
    }

    @Test void anAbsentHeaderIsAbsent() {
        assertThat(TraceContext.parse(null)).isEmpty();
    }

    @Test void theConstructorEnforcesTheSameRules() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TraceContext("short", "b7ad6b7169203331", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new TraceContext("0af7651916cd43dd8448eb211c80319c", "0".repeat(16), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new TraceContext("0AF7651916CD43DD8448EB211C80319C", "b7ad6b7169203331", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new TraceContext("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", 256));
        assertThatIllegalArgumentException().isThrownBy(() -> new TraceContext("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", -1));
    }

    @Test void aChildKeepsTheTraceAndFlagsWithAFreshValidParent() {
        var parent = TraceContext.parse(VALID).orElseThrow();
        var parents = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            var child = parent.child();
            assertThat(child.traceId()).isEqualTo(parent.traceId());
            assertThat(child.flags()).isEqualTo(parent.flags());
            assertThat(child.parentId()).isNotEqualTo(parent.parentId());
            assertThat(TraceContext.parse(child.traceparent())).contains(child);
            parents.add(child.parentId());
        }
        assertThat(parents.size()).isGreaterThan(990);
    }
}
