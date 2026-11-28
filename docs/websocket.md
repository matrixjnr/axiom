---
title: WebSocket
parent: Guides
nav_order: 15
---

# WebSocket

WebSocket is opt-in: registering a path with `Application.webSocket` is the only thing that turns an
HTTP/1.1 listener into a WebSocket listener. The API lives in `axiom-core` and uses only JDK types;
the Netty codec stays inside `axiom-http`, and no new dependency is added to core. Nothing changes
for applications that register no WebSocket route.

```java
var app = Axiom.create();
app.webSocket("/echo", new WebSocketHandler() {
    @Override
    public void onConnect(WebSocketSession session) {
        // The session is ready; send a greeting or attach application state.
    }

    @Override
    public void onMessage(WebSocketSession session, WebSocketMessage message) {
        session.send(message); // text stays text, binary stays binary
    }
});
var server = app.listen(8080);
```

The path is a route-style template with the same validation as an HTTP route, but WebSocket routes
have their own table: a normal GET on the path still runs the ordinary route, and global middleware
and error handlers never run for a WebSocket session. Registration follows the configuration freeze:
`webSocket` throws `IllegalStateException` after `start()` and rejects a duplicate path shape with
`IllegalArgumentException`. The compiled [example](../examples/modules/src/main/java/example/modules/WebSocketExample.java)
shows both an echo route and its in-memory test.

## Handshake

An upgrade is attempted only for an HTTP/1.1 GET with an `Upgrade: websocket` request field.

| Request | Result |
| --- | --- |
| No WebSocket route matches the path | HTTP 501, as before |
| GET, `Connection` includes `Upgrade`, `Sec-WebSocket-Key` is one base64 16-byte nonce, `Sec-WebSocket-Version: 13`, no extensions and no body | HTTP 101 with the computed `Sec-WebSocket-Accept` |
| Any other upgrade, including non-GET, missing or malformed fields, a version other than 13, extensions or a body | HTTP 400 |
| No stream slot available | HTTP 503 |

HEAD and OPTIONS requests keep their normal routing even on a registered path, and an unregistered
upgrade still receives the listener's original 501. The session's `request()` and `execution()` are
the immutable metadata of the HTTP GET that opened it and remain readable after close. A live
session exposes the socket peer through `remoteAddress()`; an in-memory one does not.

## Messages, limits and control frames

`WebSocketMessage` is immutable. `text` and `binary` factories copy their input, `bytes()` returns a
fresh copy, and the two payload kinds are never mixed. Text is validated as UTF-8.

- The largest accepted frame and the largest aggregated text or binary message are both 64 KiB.
  Fragmented frames are aggregated before the handler sees one message. An oversized or invalid
  frame receives close code 1009 or 1007 and fires `onError` before `onClose`.
- Ping frames are answered with a pong by the transport; pongs are not delivered to the handler.
  `session.ping(bytes)` sends an application ping. Close frames are echoed and end the session.
- A close code must be a valid RFC 6455 code; 1005 and 1006 are reserved. A reason is at most 123
  UTF-8 bytes. `close()` uses code 1000.
- At most 16 writes are outstanding before the listener stops reading frames. Reads resume when
  those writes drain and the channel is writable again, so a peer that does not read cannot make the
  server buffer frames without bound. The listener's idle and response timeouts still apply.

## Ownership, errors and shutdown

A `WebSocketHandler` instance is shared by every session on its route and must be thread-safe.
Callbacks are serialized per session but different sessions call it concurrently. A failure in a
callback is reported to `onError` for that session and does not by itself close it; a framing failure
closes it afterwards. A failure inside `onError` is logged. Session methods may be called from any
thread; sending on a closed session throws `IllegalStateException`.

The listener owns the connection and one stream slot for it. The slot is released exactly once when
the session ends, whether the peer closed, the handler called `close`, or the listener shut down.
`onClose` runs once for every session. `Server.close()` sends close code 1001 to open sessions inside
the listener's shutdown grace period; after that period the listener closes what remains. A session
whose peer disappears without a close frame reports code 1006. See [HTTP listeners](http.md) and
[admission](admission.md) for the surrounding lifecycle.

`TestClient.webSocket(target, handler)` connects the registered handler to a test handler in memory,
with the same message, error and close semantics and the same 64 KiB bound, but no socket. Closing
the test client closes its open sessions with code 1001. This lets embedded tests exercise the same
handler without binding a port:

```java
try (var client = TestClient.start(app)) {
    var session = client.webSocket("/echo", testHandler);
    session.sendText("hello");
    session.close();
}
```

## Metrics and limitations

With a real `Metrics` registry the listener records `axiom.websocket.connections` tagged by
`listener` and `outcome` (`opened`, `closed`, `refused`), and `axiom.websocket.frames` tagged by
`listener`, `direction` (`in`, `out`) and `kind` (`text`, `binary`, `control`). All tag values come
from fixed sets in the transport, so the number of series is bounded.

WebSocket runs on the HTTP/1.1 listener only: HTTP/2 extended CONNECT is not supported. Subprotocols
and permessage-deflate extensions are not negotiated, named path parameters are matched but not
exposed to the handler, and there is no per-route WebSocket stream override (the application's
aggregate stream bound applies). An in-memory ping has no application-visible pong callback, because
there is no transport to answer it.
