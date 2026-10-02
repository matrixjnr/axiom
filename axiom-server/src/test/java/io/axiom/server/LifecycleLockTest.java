package io.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.execution.AdmissionPolicy;
import io.axiom.execution.AdmissionSnapshot;
import io.axiom.http.Request;
import io.axiom.http.spi.HttpTransportProvider;
import io.axiom.lifecycle.Server;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class LifecycleLockTest {
    @Test
    @Timeout(10)
    void requestPathDoesNotWaitForTheLifecycleMonitor() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var app = Axiom.create(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var route = app.get("/", ctx -> "ok");
            app.admissionPolicy(route, AdmissionPolicy.reject(2));
            app.start();
            var holder = executor.submit(() -> {
                synchronized (app) {
                    held.countDown();
                    release.await();
                }
                return null;
            });
            try {
                assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
                var calls = executor.submit(() -> {
                    assertThat(app.handle(Request.get("/")).body()).isEqualTo("ok");
                    assertThat(app.resolve(Request.get("/"))).contains(route);
                    assertThat(app.admissionPolicy(route)).isEqualTo(AdmissionPolicy.reject(2));
                    assertThat(app.admissionPolicy()).isEqualTo(AdmissionPolicy.reject(36));
                    assertThat(app.routes()).containsExactly(route);
                    return null;
                });
                calls.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(10)
    void bindingRunsOutsideTheLockAndAListenerBoundAfterCloseIsClosed(@TempDir Path services) throws Exception {
        var file = services.resolve("META-INF/services/" + HttpTransportProvider.class.getName());
        Files.createDirectories(file.getParent());
        Files.writeString(file, BlockingProvider.class.getName() + "\n", StandardCharsets.UTF_8);
        var loader = new ServicesLoader(file.toUri().toURL(), getClass().getClassLoader());
        BlockingProvider.reset();
        var app = Axiom.create();
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            app.get("/", ctx -> "ok");
            var listen = executor.submit(() -> {
                Thread.currentThread().setContextClassLoader(loader);
                return app.listen(new InetSocketAddress("127.0.0.1", 0));
            });
            assertThat(BlockingProvider.binding.await(5, TimeUnit.SECONDS)).isTrue();
            // Closing needs the lifecycle lock; it must not wait for the blocked bind.
            executor.submit(app::close).get(5, TimeUnit.SECONDS);
            assertThat(app.state()).isEqualTo(Application.State.CLOSED);
            BlockingProvider.release.countDown();
            assertThatThrownBy(() -> listen.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
            assertThat(BlockingProvider.server.closed.get()).isTrue();
            assertThatIllegalStateException().isThrownBy(() -> app.handle(Request.get("/")));
        } finally {
            BlockingProvider.release.countDown();
            thread.setContextClassLoader(previous);
            app.close();
        }
    }

    /** Exposes one service file on top of the test class path. */
    private static final class ServicesLoader extends ClassLoader {
        private final URL services;

        ServicesLoader(URL services, ClassLoader parent) {
            super(parent);
            this.services = services;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (name.equals("META-INF/services/" + HttpTransportProvider.class.getName())) {
                return Collections.enumeration(java.util.List.of(services));
            }
            return super.getResources(name);
        }
    }

    /** Transport whose bind blocks until the test releases it. */
    public static final class BlockingProvider implements HttpTransportProvider {
        static volatile CountDownLatch binding;
        static volatile CountDownLatch release;
        static volatile FakeServer server;

        static void reset() {
            binding = new CountDownLatch(1);
            release = new CountDownLatch(1);
            server = null;
        }

        @Override
        public Server bind(Application application, InetSocketAddress address) throws IOException {
            binding.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) { throw new IOException("Bind was not released"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            server = new FakeServer(address);
            return server;
        }
    }

    static final class FakeServer implements Server {
        final AtomicBoolean closed = new AtomicBoolean();
        private final InetSocketAddress address;
        private final CompletableFuture<Void> termination = new CompletableFuture<>();

        FakeServer(InetSocketAddress address) { this.address = address; }

        @Override public InetSocketAddress localAddress() { return address; }
        @Override public boolean isOpen() { return !closed.get(); }
        @Override public AdmissionSnapshot admission() { return new AdmissionSnapshot(0, 0, 0, 0, 0); }
        @Override public CompletionStage<Void> termination() { return termination; }

        @Override
        public void close() {
            closed.set(true);
            termination.complete(null);
        }
    }
}
