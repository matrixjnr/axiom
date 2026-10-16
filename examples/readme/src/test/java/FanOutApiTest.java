import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class FanOutApiTest {
    private static ExecutionContext execution() {
        return ExecutionContext.create(Duration.ofSeconds(10));
    }

    @Test void joinsParallelLookupsBeforeEncoding() throws Exception {
        var started = new CountDownLatch(2);
        FanOutApi.Lookup lookup = (id, budget) -> {
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            assertThat(budget).isPositive();
            started.countDown();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            return id;
        };
        try (var client = TestClient.start(FanOutApi.create(lookup, lookup))) {
            var response = client.get("/summary/42");
            assertThat(response.status()).isEqualTo(200);
            assertThat(new String((byte[]) response.body(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"profile\":\"42\",\"recommendations\":\"42\"}");
        }
    }

    @Test void observesEitherChildFailureAndWaitsForSiblingCleanup() {
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var failure = new IllegalArgumentException("lookup failed");
        FanOutApi.Lookup blocked = (id, budget) -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            finally { stopped.countDown(); }
            return "unreachable";
        };
        FanOutApi.Lookup failing = (id, budget) -> {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            throw failure;
        };
        assertThatThrownBy(() -> FanOutApi.fetch("42", execution(), blocked, failing)).isSameAs(failure);
        assertThat(stopped.getCount()).isZero();
    }

    @Test void preservesDownstreamTimeout() {
        var failure = new TimeoutException("downstream timeout");
        assertThatThrownBy(() -> FanOutApi.fetch("42", execution(), (id, budget) -> { throw failure; },
                (id, budget) -> "ok")).isSameAs(failure);
    }

    @Test void interruptingParentWaitsForBothChildrenToStop() throws Exception {
        var started = new CountDownLatch(2);
        var stopped = new CountDownLatch(2);
        var parentDone = new CountDownLatch(1);
        FanOutApi.Lookup blocked = (id, budget) -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            finally { stopped.countDown(); }
            return "unreachable";
        };
        var failure = new AtomicReference<Throwable>();
        var parent = Thread.ofVirtual().start(() -> {
            try {
                assertThatThrownBy(() -> FanOutApi.fetch("42", execution(), blocked, blocked))
                        .isInstanceOf(InterruptedException.class);
                assertThat(stopped.getCount()).isZero();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally { parentDone.countDown(); }
        });
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            parent.interrupt();
            assertThat(parentDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stopped.getCount()).isZero();
            assertThat(failure.get()).isNull();
        } finally {
            parent.interrupt();
            parent.join(5000);
        }
    }
}
