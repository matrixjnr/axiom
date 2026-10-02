package io.axiom.http.internal;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Tracks every Netty buffer (paranoid level) and fails the test after which a leak is reported.
 * Leaks surface only after garbage collection, so a report can land one test late; a clean run
 * stays clean. Registered for all tests through extension autodetection.
 */
public final class LeakDetection implements AfterEachCallback {
    private static final List<String> LEAKS = new ArrayList<>();

    static {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(new ResourceLeakDetectorFactory() {
            @SuppressWarnings("deprecation") // The abstract factory method; the two-argument form delegates here.
            @Override public <T> ResourceLeakDetector<T> newResourceLeakDetector(
                    Class<T> resource, int samplingInterval, long maxActive) {
                return new Recording<>(resource, samplingInterval);
            }
        });
    }

    /** Creates the extension; buffers allocated before this class initializes are not tracked. */
    public LeakDetection() { }

    @Override public void afterEach(ExtensionContext context) {
        // In paranoid mode every allocation drains collected, unreleased buffers into reports.
        // Weak references are enqueued asynchronously after collection, hence a few rounds.
        for (int round = 0; round < 5; round++) {
            System.gc();
            UnpooledByteBufAllocator.DEFAULT.buffer(1).release();
            synchronized (LEAKS) { if (!LEAKS.isEmpty()) { break; } }
        }
        List<String> leaks;
        synchronized (LEAKS) {
            leaks = List.copyOf(LEAKS);
            LEAKS.clear();
        }
        if (!leaks.isEmpty()) { throw new AssertionError("Netty resource leak:\n" + String.join("\n", leaks)); }
    }

    private static final class Recording<T> extends ResourceLeakDetector<T> {
        Recording(Class<?> resource, int samplingInterval) { super(resource, samplingInterval); }
        @Override protected boolean needReport() { return true; }
        @Override protected void reportTracedLeak(String resource, String records) { record(resource + records); }
        @Override protected void reportUntracedLeak(String resource) { record(resource); }
        private static void record(String leak) { synchronized (LEAKS) { LEAKS.add(leak); } }
    }
}
