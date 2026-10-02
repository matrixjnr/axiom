package io.axiom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdmissionPolicyTest {
    @Test void validatesCapacitiesAndQueueBudget() {
        assertThat(AdmissionPolicy.reject(3)).isEqualTo(new AdmissionPolicy(3, 0, Duration.ZERO));
        assertThatThrownBy(() -> AdmissionPolicy.reject(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionPolicy(1, -1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionPolicy(1, 0, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionPolicy(1, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionPolicy(1, 1, Duration.ofDays(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionPolicy(1, 1, null)).isInstanceOf(NullPointerException.class);
    }
}
