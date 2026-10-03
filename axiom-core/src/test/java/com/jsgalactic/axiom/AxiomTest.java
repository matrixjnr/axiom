package com.jsgalactic.axiom;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.application.spi.ApplicationProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AxiomTest {
    @Test
    void explainsMissingRuntime() {
        assertThatIllegalStateException().isThrownBy(Axiom::create)
                .withMessageContaining("Add axiom-server");
    }

    @Test
    void rejectsAmbiguousProvidersBeforeCreatingAnApplication(@TempDir Path directory) throws Exception {
        var services = directory.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(ApplicationProvider.class.getName()),
                FirstProvider.class.getName() + "\n" + SecondProvider.class.getName() + "\n");
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            assertThatIllegalStateException().isThrownBy(Axiom::create)
                    .withMessageContaining("Multiple Axiom runtime providers");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public static final class FirstProvider implements ApplicationProvider {
        public FirstProvider() {}
        @Override
        public Application create() { throw new AssertionError("Provider must not be invoked"); }
    }

    public static final class SecondProvider implements ApplicationProvider {
        public SecondProvider() {}
        @Override
        public Application create() { throw new AssertionError("Provider must not be invoked"); }
    }
}
