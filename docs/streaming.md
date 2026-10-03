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
  deadline also interrupts a handler that is blocked on something else. Streams therefore live at
  most as long as `app.requestTimeout(...)`, ten seconds by default.
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

## Shutdown

Closing a listener (or the application) **cancels** open streams at once, with reason `SHUTDOWN`,
and closes their connections without a final chunk, instead of waiting out the five-second grace
period: a stream may never end by itself, and a body that is silently completed early would look
like a finished download. Clients of a cut stream reconnect (browsers do so automatically for
server-sent events; send `retry:` to set the delay). A stream that has not sent its head yet when
shutdown begins is refused with 503 like a request that was still waiting. Other running requests
keep the grace period, as described in [HTTP listeners](http.md#ownership-and-shutdown).

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
outlive ten seconds needs a longer `app.requestTimeout(...)`, which applies to every route.

## Metrics

Streams are measured as part of the [metrics](observability.md#metrics): they count as requests
(latency covers the whole stream; the status class is the head's, except that a stream that fails
counts as `5xx` and one cut by a disconnect or shutdown as `cancelled`) and hold an active slot, plus

| Name | Kind | Tags | Meaning |
| --- | --- | --- | --- |
| `axiom.http.streams` | counter | `outcome` | finished streams: `completed`, `client_disconnected`, `limit_exceeded`, `timeout`, `shutdown`, `failed` |
| `axiom.http.stream.bytes` | counter | none | body bytes written |
| `axiom.http.streams.active` | gauge | none | streams whose head was sent and whose body is running |
| `axiom.http.stream.backpressure` | counter | none | writes that had to wait for a slow client |

`outcome` is the only tag and takes one of six fixed values. No path, query, header or identity is
ever a tag.

## Testing

`TestClient.stream(request)` returns the response head and a `StreamedResponse`: `status()`,
`headers()`, `next()` / `nextText()` (one chunk per handler write, empty at the end), `readAll()`,
`completion()` and `close()`. The handler's writer and the reader meet at a hand-off holding one
chunk, so the handler is never more than one write ahead of the test: a test that does not read
stops the handler at its next write, and one that reads sees every write in order, with no
sleeping.

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
`CLIENT_DISCONNECTED` and a handler that is not writing is interrupted. `completion()` completes
normally when the body returned, and exceptionally with what ended it. `TestClient.execute` and
`submit` run a finite stream to its end and return one response with the whole body; they apply
the same cap and deadline and rethrow a failing body. HEAD gets no body. See
[the programming model](programming-model.md#testing-without-ports) for the rest of the test
client. The hand-off models ordering and backpressure; the listener's water marks, 16 KiB
splitting and stall bound exist only over a socket.

## Limitations

- A stream lives at most as long as the request deadline, which is one application-wide setting.
- Closing a listener cancels streams instead of draining them.
- Open streams hold an admission slot and a virtual thread each.
- Middleware and error handlers run before the body and cannot see or wrap it.
- The test client models the hand-off, not the transport's buffering.
- Stream metrics are not tagged by route.

These are tracked in the limitations index, [#13](https://github.com/matrixjnr/axiom/issues/13).
