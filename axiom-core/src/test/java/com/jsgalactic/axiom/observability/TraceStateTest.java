package com.jsgalactic.axiom.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TraceStateTest {
    private static final String VALID = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Test void parsesMembersInOrderAndToleratesWhitespaceAndEmptyElements() {
        var state = TraceState.parse("congo=t61rcWkgMzE, rojo=00f067aa0ba902b7 ,,\tvendor@sys=a b").orElseThrow();
        assertThat(state.members()).extracting(TraceState.Member::key).containsExactly("congo", "rojo", "vendor@sys");
        assertThat(state.get("rojo")).contains("00f067aa0ba902b7");
        assertThat(state.get("vendor@sys")).contains("a b");
        assertThat(state.get("absent")).isEmpty();
        assertThat(state.header()).isEqualTo("congo=t61rcWkgMzE,rojo=00f067aa0ba902b7,vendor@sys=a b");
        assertThat(TraceState.parse(state.header())).contains(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ",", "novalue", "=value", "UPPER=x", "1abc=x", "a=b=c", "k=v,k=w", "a b=c", "k=é",
            "k=va,lue", "k=\u0001", "tenant@=x", "@sys=x", "t@s@y=x", "t@1sys=x"})
    void rejectsAnythingThatBreaksTheGrammarAsAWhole(String header) {
        assertThat(TraceState.parse(header)).isEmpty();
    }

    @Test void enforcesTheKeyAndValueLengthsAndTheMemberAndSizeLimits() {
        assertThat(TraceState.parse("a" + "b".repeat(255) + "=v")).isPresent();
        assertThat(TraceState.parse("a" + "b".repeat(256) + "=v")).isEmpty();
        assertThat(TraceState.parse("k=" + "v".repeat(256))).isPresent();
        assertThat(TraceState.parse("k=" + "v".repeat(257))).isEmpty();
        assertThat(TraceState.parse("0" + "a".repeat(240) + "@s" + "b".repeat(13) + "=v").map(s -> s.members().size())).contains(1);
        assertThat(TraceState.parse("t@s" + "b".repeat(14) + "=v")).isEmpty();
        var many = new ArrayList<String>();
        for (int i = 0; i < 32; i++) { many.add("k" + i + "=v"); }
        assertThat(TraceState.parse(String.join(",", many))).isPresent();
        many.add("k32=v");
        assertThat(TraceState.parse(String.join(",", many))).isEmpty();
        assertThat(TraceState.parse("a=" + "v".repeat(256) + ",b=" + "v".repeat(256))).isEmpty(); // over 512 characters
        assertThat(TraceState.parse(null)).isEmpty();
    }

    @Test void aChangedMemberMovesToTheFrontAndTheRightmostFallOffAtTheLimit() {
        var state = TraceState.parse("a=1,b=2,c=3").orElseThrow();
        assertThat(state.with("b", "9").header()).isEqualTo("b=9,a=1,c=3");
        assertThat(state.with("d", "4").header()).isEqualTo("d=4,a=1,b=2,c=3");
        var full = new ArrayList<TraceState.Member>();
        for (int i = 0; i < 32; i++) { full.add(new TraceState.Member("k" + i, "v")); }
        var next = new TraceState(full).with("new", "x");
        assertThat(next.members()).hasSize(32);
        assertThat(next.members().getFirst().key()).isEqualTo("new");
        assertThat(next.get("k31")).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> state.with("Bad", "x"));
        assertThatIllegalArgumentException().isThrownBy(() -> state.with("k", "a,b"));
        assertThatIllegalArgumentException().isThrownBy(() -> state.with("k", "v".repeat(257)));
        assertThat(TraceState.EMPTY.isEmpty()).isTrue();
        assertThat(TraceState.EMPTY.header()).isEmpty();
    }

    @Test void constructorsRejectInvalidMembers() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TraceState.Member("Bad", "v"));
        assertThatIllegalArgumentException().isThrownBy(() -> new TraceState.Member("k", "a=b"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TraceState(List.of(new TraceState.Member("k", "1"), new TraceState.Member("k", "2"))));
        var tooLong = new ArrayList<TraceState.Member>();
        for (int i = 0; i < 3; i++) { tooLong.add(new TraceState.Member("k" + i, "v".repeat(200))); }
        assertThatIllegalArgumentException().isThrownBy(() -> new TraceState(tooLong));
    }

    @Test void theContextCarriesTheStateOnlyWithAValidTraceparentAndPassesItToTheChild() {
        var context = TraceContext.parse(VALID, "congo=t61rcWkgMzE").orElseThrow();
        assertThat(context.traceState().get("congo")).contains("t61rcWkgMzE");
        var child = context.child();
        assertThat(child.traceState()).isEqualTo(context.traceState());
        assertThat(child.parentId()).isNotEqualTo(context.parentId());
        assertThat(child.headers()).containsEntry("traceparent", child.traceparent())
                .containsEntry("tracestate", "congo=t61rcWkgMzE").hasSize(2);
        assertThat(TraceContext.parse(VALID, "broken").orElseThrow().traceState()).isEqualTo(TraceState.EMPTY);
        assertThat(TraceContext.parse(VALID, null).orElseThrow().headers()).containsOnlyKeys("traceparent");
        assertThat(TraceContext.parse("garbage", "congo=x")).isEmpty();
        assertThat(TraceContext.parse(VALID).orElseThrow()).isEqualTo(new TraceContext("0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331", 1));
    }

    @Test void correlationNamesTheRequestAndTheTraceWhenThereIsOne() {
        assertThat(TraceContext.correlation("req-1", java.util.Optional.empty())).isEqualTo("req-1");
        assertThat(TraceContext.correlation("req-1", TraceContext.parse(VALID)))
                .isEqualTo("req-1 trace=0af7651916cd43dd8448eb211c80319c");
    }
}
