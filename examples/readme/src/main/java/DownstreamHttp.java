import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Example adapter: borrows an application-owned HttpClient; never closes it per request. */
final class DownstreamHttp {
    static final int MAX_BODY_BYTES = 64 * 1024;

    private DownstreamHttp() {}

    static String fetch(HttpClient client, URI uri, Duration budget) throws Exception {
        long started = System.nanoTime();
        if (budget.isZero() || budget.isNegative()) { throw new TimeoutException(); }
        long nanos = budget.toNanos();
        var request = HttpRequest.newBuilder(uri).timeout(budget).GET().build();
        var response = client.sendAsync(request, info -> new LimitedText());
        try {
            // Bound the entire buffered body wait, including when headers already arrived.
            long remaining = nanos - (System.nanoTime() - started);
            if (remaining <= 0) { throw new TimeoutException(); }
            var result = response.get(remaining, TimeUnit.NANOSECONDS);
            if (result.statusCode() != 200) { throw new IOException("Downstream status " + result.statusCode()); }
            return result.body();
        } catch (ExecutionException failure) {
            var cause = failure.getCause();
            if (cause instanceof Exception exception) { throw exception; }
            if (cause instanceof Error error) { throw error; }
            throw failure;
        } finally {
            // Cancel the original exchange future, not a dependent transformation.
            response.cancel(true);
        }
    }

    /** Consume incrementally; no streaming handle escapes the request task. */
    private static final class LimitedText implements BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<String> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) { return; }
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Downstream body exceeds 64 KiB"));
                    return;
                }
                var chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }

        @Override public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
    }
}
