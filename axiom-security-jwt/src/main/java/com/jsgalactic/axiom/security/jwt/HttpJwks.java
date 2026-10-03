package com.jsgalactic.axiom.security.jwt;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The bounded HTTP exchange behind {@link JwksSource#url(URI, Duration)}. */
final class HttpJwks {
    private HttpJwks() {}

    static byte[] get(HttpClient client, URI uri, Duration timeout, int maxBytes) throws IOException {
        var request = HttpRequest.newBuilder(uri).GET().timeout(timeout)
                .header("Accept", "application/jwk-set+json, application/json").build();
        var future = client.sendAsync(request, info -> new Bounded(info, maxBytes));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS).body();
        } catch (TimeoutException slow) {
            future.cancel(true);
            throw new IOException("The key set fetch took longer than " + timeout.toMillis() + " ms", slow);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("The key set fetch was interrupted", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) { throw new IOException(io.getMessage(), io); }
            throw new IOException("The key set fetch failed: " + failed.getCause().getClass().getSimpleName(), failed.getCause());
        }
    }

    /** Collects a body up to a limit, failing early on status, content type, declared and actual length. */
    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final List<byte[]> chunks = new ArrayList<>();
        private final int maxBytes;
        private final String rejection;
        private Flow.Subscription subscription;
        private int total;

        Bounded(HttpResponse.ResponseInfo info, int maxBytes) {
            this.maxBytes = maxBytes;
            if (info.statusCode() != 200) {
                rejection = "The key set endpoint answered status " + info.statusCode()
                        + (info.statusCode() / 100 == 3 ? " (redirects are not followed)" : "");
            } else if (!jsonType(info.headers().firstValue("Content-Type").orElse(""))) {
                rejection = "The key set has an unexpected content type";
            } else if (info.headers().firstValueAsLong("Content-Length").orElse(0) > maxBytes) {
                rejection = "The key set is longer than " + maxBytes + " bytes";
            } else {
                rejection = null;
            }
        }

        private static boolean jsonType(String value) {
            var type = value.toLowerCase(Locale.ROOT);
            int semicolon = type.indexOf(';');
            if (semicolon >= 0) { type = type.substring(0, semicolon); }
            type = type.strip();
            return type.equals("application/json") || type.equals("application/jwk-set+json");
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (rejection != null) {
                subscription.cancel();
                result.completeExceptionally(new IOException(rejection));
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) { return; }
            for (var buffer : items) {
                total += buffer.remaining();
                if (total > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("The key set is longer than " + maxBytes + " bytes"));
                    return;
                }
                var bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                chunks.add(bytes);
            }
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            var all = new byte[total];
            int offset = 0;
            for (var chunk : chunks) {
                System.arraycopy(chunk, 0, all, offset, chunk.length);
                offset += chunk.length;
            }
            result.complete(all);
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }
    }
}
