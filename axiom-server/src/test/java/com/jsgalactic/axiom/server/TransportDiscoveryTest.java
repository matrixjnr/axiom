package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import org.junit.jupiter.api.Test;

class TransportDiscoveryTest {
    @Test void missingTransportLeavesApplicationConfigurable() {
        try (var app = Axiom.create()) {
            assertThatThrownBy(() -> app.listen(0)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("add axiom-http");
            assertThat(app.state()).isEqualTo(Application.State.CONFIGURING);
            app.get("/", ctx -> "still configurable");
        }
    }
}
