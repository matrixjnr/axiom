---
title: Streaming and SSE
parent: Guides
nav_order: 8
---

# Streaming responses and server-sent events

A handler normally returns a complete body, which is limited to 1 MiB and held in memory. When the
body is large, produced over time, or has no end, return a **stream** instead. The handler's virtual
thread writes the body to the connection as it produces it, a slow client holds the handler back
instead of growing a buffer, and the size, the time and the number of streams are all bounded.

```java
app.get("/export", ctx -> Response.stream(200, "text/csv", out -> {
    out.write("id,name\n");
    for (var row : rows.scan()) {                 // any blocking source
        out.write(row.id() + "," + row.name() + "\n");
    }
}));

app.get("/events", ctx -> Response.sse(events -> {
    events.send(ServerSentEvent.named("price", "42.50").withId("1001"));
    while (subscription.isOpen()) {
        var update = subscription.poll(15, TimeUnit.SECONDS);
        if (update == null) { events.keepAlive(); } else { events.send(update.toJson()); }
    }
}));
```

Streams are core API (`com.jsgalactic.axiom.http`); core knows nothing about Netty or any codec.

## The model

`Response.stream(status, contentType, [maxBytes,] body)` returns a response whose body is a
`StreamBody`. The runtime sends the status line and headers first, then calls
`body.writeTo(out)` once, **on the same virtual thread that ran the handler**. So the request keeps
its admission slot, its deadline and its interruption behavior for as long as the stream lasts
(see [execution](execution.md) and [admission](admission.md)), and a stream that is not finished
holds one of the active slots (36 by default).

- The response ends when `writeTo` returns normally. HTTP/1.1 clients receive chunked transfer
  encoding with a final chunk, so the connection stays usable for the next request. An HTTP/1.0
  client has no chunked coding: the body is unframed and ends with the connection, which then
  closes.
- Headers are final once sent. Application `Content-Length`, `Transfer-Encoding` and other
  hop-by-hop headers are ignored, and `Date` and `X-Request-ID` are added as for any response.
  Headers over the usual size and Latin-1 rules are refused **before** the head is sent with an
  ordinary 500, and the body does not run. Status 204, 205 and 304 cannot carry a body and are
  refused when the response is built.
- A stream is not a value a codec encodes: `Response.stream` bodies bypass codecs and content
  negotiation, so you write the bytes you want. `Response.of` refuses a `StreamBody`.
- **HEAD** receives the status line and headers of the GET response with no body, no
  `Transfer-Encoding` and no `Content-Length`, and the body is not run. Nothing is written after the head, so the
  connection stays usable.

## Writing: backpressure and bounds

`BodyWriter` has `write(byte[], offset, length)`, `write(byte[])`, `write(String)` (UTF-8) and
`bytesWritten()`. Each call copies its bytes and hands them to the connection at once; there is no
separate flush, and each call is at least one chunk on the wire. Writes of more than 16 KiB are split into
16 KiB chunks. A zero-length write sends nothing.

- **Backpressure.** Before each chunk the writer waits until the channel is writable. The channel
  becomes unwritable at 128 KiB of queued output and writable again below 32 KiB, so the data
  queued for one slow client is at most 128 KiB plus one chunk, however much the handler writes.
  The handler is blocked inside `write` meanwhile, on its virtual thread, which costs no platform
  thread.
- **Deadline.** A wait ends at the request deadline (the writer then aborts with `TIMEOUT`), and the
  deadline also interrupts a handler that is blocked on something else. A stream therefore lives at
  most as long as `app.requestTimeout(...)` (ten seconds by default) unless its response gives it
  a [lifetime of its own](#stream-lifetime).
- **Stalled clients.** A client that takes no data for the listener's response write bound
  (`responseTimeout`, 30 seconds by default; see [listener options](http.md#listener-options)
  and [resource limits](http.md#resource-limits)) is dropped (`CLIENT_DISCONNECTED`), as the same bound drops
  a client that reads a buffered response too slowly.
- **Byte cap.** Every stream has a cap on the bytes it may write: 64 MiB
  (`Response.DEFAULT_STREAM_LIMIT`) unless the response sets another. A write that would cross the
  cap sends nothing and aborts the stream with `LIMIT_EXCEEDED`; the cap is checked per write,
  not per chunk. A body of exactly the cap completes.

## When a stream cannot continue

The first problem aborts the writer for good: the write throws `StreamAbortedException` whose
`reason()` is one of

| Reason | Cause |
| --- | --- |
| `CLIENT_DISCONNECTED` | the connection closed, a chunk failed to write, or the client stopped reading for 30 seconds |
| `LIMIT_EXCEEDED` | the byte cap |
| `TIMEOUT` | the request deadline |
| `SHUTDOWN` | the listener is closing, or the request was cancelled |

Every later write throws it again, so catching it and continuing does not revive the stream. The
handler should let it propagate, or return. Either way the runtime then:

1. closes the connection **without a final chunk** and sends no second response, because the
   status line is already on the wire (a client sees a truncated chunked body, which is how a
   cut-off download must look);
2. releases the buffers, the virtual thread and the admission slot;
3. does not offer the failure to [error handlers](middleware.md): they choose a response, and the
   response is already under way. Exceptions other than a `StreamAbortedException` are logged at
   error level with the request ID, exactly like other handler failures; a client leaving or
   shutdown is not logged (debug level), an exceeded cap is a warning.

A body that returns normally after swallowing an abort is treated as failed in the same way. A
writer is valid only until `writeTo` returns; using it afterwards throws `IllegalStateException`.

## Stream lifetime

`Response.withStreamLifetime(Duration)` lets one stream outlive the request deadline without
loosening the deadline of any other request:

```java
app.get("/events", ctx -> Response.sse(events -> { /* ... */ })
        .withStreamLifetime(Duration.ofHours(1)));
```

Once the head is sent, the request's deadline moves to the lifetime from that moment, if that is
later. The stream then ends with `TIMEOUT` when the lifetime passes (and a handler blocked elsewhere
is interrupted then), and it holds its admission slot all that time. The lifetime is explicit and
bounded: positive, at most one day (`Response.MAX_STREAM_LIFETIME`). It never shortens a deadline,
so a lifetime below what remains of the request deadline changes nothing. A stream without one keeps
the request deadline as before. Until the head is sent, the ordinary deadline applies.

## Observing and wrapping a stream

Middleware and error handlers finish before the body runs, so what happens to the body is told to
callbacks the response carries:

```java
app.use((ctx, next) -> {
    var response = next.run();
    if (!response.isStreaming()) { return response; }
    return response
            .mapStream(body -> out -> { timer.start(); body.writeTo(out); })      // wrap the body
            .onStreamEnd(outcome -> accessLog.stream(ctx.request().path(), outcome)); // learn the end
});
```

- `onStreamEnd(Consumer<StreamOutcome>)` is called once when the body is over, on the handler's
  thread, with a `StreamOutcome`: `kind` (`COMPLETED`, `CLIENT_DISCONNECTED`, `LIMIT_EXCEEDED`,
  `TIMEOUT`, `SHUTDOWN` or `FAILED`), `bytesWritten`, `elapsed` since the head was sent, and the
  `failure` the body threw, if any. It is called for exactly the streams that run: not for a HEAD
  request, and not for a response refused before its head was sent. The first observer added runs
  first, so inner middleware sees the end before outer middleware. Observers must be quick and must
  not block: the request still holds its admission slot. One that throws is logged and ignored, and
  the others still run. They observe; they cannot change the status or send a second response.
- `mapStream(UnaryOperator<StreamBody>)` replaces the body with a wrapper of it, to time it, count or
  transform what it writes. Wrap the `BodyWriter` too and delegate `shutdownRequested` and
  `onShutdown` to the writer you wrap.
- Error handlers cannot choose a response for a failing body because the response is already under
  way. A handler that produced a streamed response (or any middleware) learns of such a failure,
  including a client disconnect or an exceeded cap, from the same `onStreamEnd` callback.

## Shutdown

Closing a listener (or the application) lets an open stream keep the **shutdown grace period**
(five seconds by default, `ListenerOptions.shutdownGrace`), like other running requests, and tells
the body that shutdown began:

- `BodyWriter.shutdownRequested()` (and `EventSink.shutdownRequested()`) turns true, and actions
  registered with `onShutdown(Runnable)` run once, at once if shutdown already began. An action wakes a body that is
  blocked on something other than a write, for example by completing a future or closing a
  subscription so a blocking `poll` returns. It runs on a server thread and must be quick and
  non-blocking.
- A body that sees shutdown should send what completes the stream (a last event, the rest of the
  file) and return. The response then ends with a **final chunk** and the connection closes, so a
  finite download finishes and an event stream ends cleanly; browsers reconnect after the
  `retry:` delay.
- Writes keep working during the grace period. A body still running when it ends is cut like
  before: reason `SHUTDOWN`, the connection closed without a final chunk. A stream that never
  ends and ignores the signal therefore holds the close for the grace period.
- A stream whose head has not been sent yet when shutdown begins is refused with 503 like a request
  that was still waiting. The connection never serves another request after the stream: the
  response closes it, whatever `Connection` header the head carried.

```java
app.get("/events", ctx -> Response.sse(events -> {
    var closing = new CountDownLatch(1);
    events.onShutdown(closing::countDown);               // wakes the wait below
    while (!events.shutdownRequested()) {
        var update = subscription.poll(15, TimeUnit.SECONDS);
        if (update != null) { events.send(update.toJson()); } else { events.keepAlive(); }
    }
    events.send(ServerSentEvent.data("server closing").withRetry(Duration.ofSeconds(2)));
}));
```

## Server-sent events

`Response.sse([maxBytes,] body)` is a stream of type `text/event-stream` for `EventSource`. Its
headers are `Cache-Control: no-store, no-transform` and `X-Accel-Buffering: no`, so caches do not
store the stream and proxies (including nginx) do not buffer or compress it; Axiom itself never
compresses a response. The body receives an `EventSink`:

- `send(ServerSentEvent)` and `send(String data)` send one event, as one write;
- `comment(String)` sends a comment, which clients ignore;
- `keepAlive()` sends the comment `: keep-alive`. Call it when events are rare: intermediaries
  close connections they see as idle, and a write to a client that left is how the handler finds out.

`ServerSentEvent.data(text)` and `ServerSentEvent.named(name, text)` build events, with
`withId(...)` (returned by browsers as `Last-Event-ID` when they reconnect) and
`withRetry(Duration)` (whole milliseconds, 1 to `Integer.MAX_VALUE`).

**Line breaks are what separate fields in this format, so they are never passed through.**

- A CR, LF or CRLF in the *data* starts another `data:` line; the client joins the lines with LF
  again. Data such as `x\nevent: admin` therefore arrives as the text `x`, a line break and
  `event: admin`, and cannot create an `event` field. The same holds for comments, which become
  one comment line per line.
- A CR or LF in an event *name* or *id*, or a NUL in an id, is rejected with
  `IllegalArgumentException` when the event is built. Empty names are rejected; an empty id is
  allowed (it resets the client's last id).

The default cap is 64 MiB; pass `maxBytes` to change it. Remember the deadline: a stream that must
outlive ten seconds needs `withStreamLifetime(...)` (see [above](#stream-lifetime)); raising
`app.requestTimeout(...)` would change every route.

## Metrics

Streams are measured as part of the [metrics](observability.md#metrics): they count as requests
(latency covers the whole stream; the status class is the head's, except that a stream that fails
counts as `5xx` and one cut by a disconnect or shutdown as `cancelled`) and hold an active slot, plus

| Name | Kind | Tags | Meaning |
| --- | --- | --- | --- |
| `axiom.http.streams` | counter | `method`, `route`, `outcome` | finished streams: `completed`, `client_disconnected`, `limit_exceeded`, `timeout`, `shutdown`, `failed` |
| `axiom.http.stream.bytes` | counter | `method`, `route` | body bytes written |
| `axiom.http.streams.active` | gauge | `method`, `route` | streams whose head was sent and whose body is running |
| `axiom.http.stream.backpressure` | counter | `method`, `route` | writes that had to wait for a slow client |

`method` and `route` are the registered route's method and template, from the same bounded set as the
request series: at most 1024 endpoints get their own series and later ones share `other`, and
requests that match no route share `none` / `unmatched`. `outcome` takes one of six fixed values. No
path, query, header or identity is ever a tag. The HTTP listener and `TestClient` record the same series.

## Testing

`TestClient.stream(request)` returns the response head and a `StreamedResponse`: `status()`,
`headers()`, `next()` / `nextText()` (one chunk per piece the handler wrote, empty at the end),
`readAll()`, `completion()`, `beginShutdown()` and `close()`. By default the handler's writer and the
reader meet at a hand-off holding one chunk, so the handler is never more than one write ahead of the
test: a test that does not read stops the handler at its next write, and one that reads sees every
write in order, with no sleeping.

```java
try (var client = TestClient.start(app); var events = client.stream(new Request("GET", "/events"))) {
    assertThat(events.headers()).containsEntry("Content-Type", "text/event-stream");
    assertThat(events.nextText()).contains("event: price\nid: 1001\ndata: 42.50\n\n");
    events.close();                                        // the client goes away
    assertThatThrownBy(() -> events.completion().get())    // CancellationException
            .isInstanceOf(CancellationException.class);
}
```

`close()` plays a disconnect: the handler's writes throw `StreamAbortedException` with
`CLIENT_DISCONNECTED` and a handler that is not writing is interrupted. `beginShutdown()` plays the
start of a listener's shutdown: `shutdownRequested()` turns true and `onShutdown` actions run, while
writes keep working, so a test can show that the body ends normally and `completion()` completes.
`completion()` completes normally when the body returned, and exceptionally with what ended it.
`TestClient.execute` and `submit` run a finite stream to its end and return one response with the
whole body; they apply the same cap, lifetime and deadline and rethrow a failing body. HEAD gets no
body. The client records the stream metrics and runs the `onStreamEnd` observers like the listener.
See [the programming model](programming-model.md#testing-without-ports) for the rest of the test client.

**Buffering.** `TestClient.start(app, StreamBuffering)` models the transport's buffer instead of the
hand-off. `StreamBuffering.listener()` has the listener's numbers: writers wait once 128 KiB are
unread and continue below 32 KiB, a write of more than 16 KiB arrives as 16 KiB pieces, and a reader
that takes nothing for 30 seconds loses the stream (`CLIENT_DISCONNECTED`). `StreamBuffering.ofBytes(n)`
sets the high water mark (low is a quarter, pieces at most 16 KiB, no stall bound);
`withStallTimeout(Duration)` sets the stall bound, and the constructor sets every number. `next()` then
returns one piece per call. Chunked framing is part of the wire only: the client delivers the payload
pieces, which are what the listener's framing carries.

## Limitations

- Open streams hold an admission slot and a virtual thread each.

These are tracked in the limitations index, [#13](https://github.com/matrixjnr/axiom/issues/13).
