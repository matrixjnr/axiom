package com.jsgalactic.axiom.server.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.execution.AdmissionSnapshot;
import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.lifecycle.Server;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The opt-in JVM shutdown hook, run on its own thread without ending the test JVM. */
@Timeout(60)
class ShutdownHookTest {
    private static final class Hooks implements DefaultApplication.ShutdownHooks {
        final List<Thread> added = new ArrayList<>();
        final List<Thread> removed = new ArrayList<>();
        boolean refuse;
        @Override public void add(Thread hook) {
            if (refuse) { throw new IllegalStateException("Shutdown in progress"); }
            added.add(hook);
        }
        @Override public void remove(Thread hook) { removed.add(hook); }
    }

    /** A listener whose termination the test completes by hand. */
    private static final class FakeServer implements Server {
        final CompletableFuture<Void> terminated = new CompletableFuture<>();
        final CountDownLatch closed = new CountDownLatch(1);
        @Override public InetSocketAddress localAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public boolean isOpen() { return closed.getCount() > 0; }
        @Override public AdmissionSnapshot admission() { return new AdmissionSnapshot(0, 0, 0, 0, 0); }
        @Override public CompletionStage<Void> termination() { return terminated.minimalCompletionStage(); }
        @Override public void close() { closed.countDown(); }
    }

    @Test void nothingIsRegisteredUnlessTheApplicationOptsIn() {
        var hooks = new Hooks();
        var app = new DefaultApplication(hooks);
        app.start();
        app.adopt(new FakeServer(), ListenerOptions.defaults());
        app.close();
        assertThat(hooks.added).isEmpty();
        assertThat(hooks.removed).isEmpty();
    }

    @Test void optingInRegistersOneHookAndAnExplicitCloseUnregistersIt() {
        var hooks = new Hooks();
        var app = new DefaultApplication(hooks);
        assertThat(app.closeOnJvmShutdown()).isSameAs(app);
        assertThat(app.closeOnJvmShutdown()).isSameAs(app);
        assertThat(hooks.added).hasSize(1);
        var hook = hooks.added.getFirst();
        app.close();
        app.close();
        assertThat(hooks.removed).containsExactly(hook);
        assertThat(hook.getState()).as("never started by an explicit close").isEqualTo(Thread.State.NEW);
        assertThatIllegalStateException().isThrownBy(app::closeOnJvmShutdown).withMessageContaining("closed");
    }

    @Test void aJvmAlreadyShuttingDownRefusesTheHookAndLeavesTheApplicationUsable() {
        var hooks = new Hooks();
        hooks.refuse = true;
        var app = new DefaultApplication(hooks);
        assertThatIllegalStateException().isThrownBy(app::closeOnJvmShutdown);
        app.close();
        assertThat(hooks.removed).isEmpty();
        assertThat(app.state()).isEqualTo(Application.State.CLOSED);
    }

    @Test void theHookClosesTheApplicationAndWaitsForEveryListenerToTerminate() throws Exception {
        var hooks = new Hooks();
        var app = new DefaultApplication(hooks);
        app.start();
        var first = new FakeServer();
        var second = new FakeServer();
        app.adopt(first, ListenerOptions.defaults());
        app.adopt(second, ListenerOptions.defaults());
        app.closeOnJvmShutdown();
        var hook = hooks.added.getFirst();
        hook.start();
        // Both listeners were told to drain, and the application no longer serves requests, which
        // is what makes a readiness probe report DOWN.
        assertThat(first.closed.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(second.closed.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(app.state()).isEqualTo(Application.State.CLOSED);
        // The hook, and with it the JVM, stays until the last listener has finished draining.
        first.terminated.complete(null);
        second.terminated.complete(null);
        hook.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(hook.isAlive()).isFalse();
        assertThat(hooks.removed).as("the running hook does not unregister itself").isEmpty();
    }

    @Test void aListenerThatNeverTerminatesCannotHoldTheJvmBeyondTheGracePlusTheMargin() throws Exception {
        var hooks = new Hooks();
        var app = new DefaultApplication(hooks, Duration.ofMillis(50));
        app.start();
        var stuck = new FakeServer();
        app.adopt(stuck, ListenerOptions.builder().shutdownGrace(Duration.ofMillis(10)).build());
        app.closeOnJvmShutdown();
        var hook = hooks.added.getFirst();
        hook.start();
        hook.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(hook.isAlive()).as("gave up waiting").isFalse();
        assertThat(stuck.closed.getCount()).isZero();
        assertThat(stuck.terminated).isNotDone();
    }
}
