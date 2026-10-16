import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.execution.ExecutionContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded parallel lookups whose lifetime stays inside the request handler. */
public final class FanOutApi {
    @FunctionalInterface
    interface Lookup {
        String fetch(String id, Duration timeout) throws Exception;
    }

    private record Part(int index, String value) {}
    public record Summary(String profile, String recommendations) {}

    static Application create(Lookup profile, Lookup recommendations) {
        var app = Axiom.create();
        app.get("/summary/:id", ctx -> {
            // Capture request values here: Context and Next never enter a child task.
            var summary = fetch(ctx.path("id"), ctx.execution(), profile, recommendations);
            return ctx.json(summary);
        });
        return app;
    }

    static Summary fetch(String id, ExecutionContext execution, Lookup profile, Lookup recommendations) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var completed = new ExecutorCompletionService<Part>(executor);
            var children = new ArrayList<Future<Part>>();
            try {
                var lookups = List.of(profile, recommendations);
                for (int i = 0; i < lookups.size(); i++) {
                    int index = i;
                    var lookup = lookups.get(i);
                    children.add(completed.submit(() -> {
                        var budget = execution.remainingTime();
                        if (budget.isZero()) { throw new TimeoutException(); }
                        return new Part(index, lookup.fetch(id, budget));
                    }));
                }
                var values = new String[2];
                for (int i = 0; i < values.length; i++) {
                    var result = completed.poll(execution.remainingTime().toNanos(), TimeUnit.NANOSECONDS);
                    if (result == null) { throw new TimeoutException(); }
                    var part = result.get();
                    values[part.index()] = part.value();
                }
                return new Summary(values[0], values[1]);
            } catch (ExecutionException failure) {
                var cause = failure.getCause();
                if (cause instanceof Exception exception) { throw exception; }
                if (cause instanceof Error error) { throw error; }
                throw failure;
            } finally {
                children.forEach(child -> child.cancel(true));
            }
        } // close waits for child termination, including after cancellation.
    }

    public static void main(String[] args) throws Exception {
        create((id, timeout) -> "Profile " + id, (id, timeout) -> "Recommendations " + id)
                .listen(8080).termination().toCompletableFuture().join();
    }
}
