package io.axiom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class BootstrapTest {
    @Test
    void java21VirtualThreadsAreAvailable() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get()).isTrue();
        }
    }
}
