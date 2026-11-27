package com.jsgalactic.axiom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StreamPolicyTest {
    @Test void rejectConfiguresAnActiveBoundWithoutAQueue() {
        var policy = StreamPolicy.reject(7);
        assertThat(policy.maxActive()).isEqualTo(7);
        assertThat(policy).isEqualTo(new StreamPolicy(7));
    }

    @Test void capacityMustBePositive() {
        assertThatThrownBy(() -> StreamPolicy.reject(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamPolicy(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
