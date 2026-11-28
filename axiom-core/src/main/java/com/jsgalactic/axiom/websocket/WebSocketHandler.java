package com.jsgalactic.axiom.websocket;

/**
 * Application callbacks for one WebSocket route. Register one with {@link
 * com.jsgalactic.axiom.application.Application#webSocket}.
 *
 * <p>A handler instance is shared by every session on its route and must be thread-safe. Callbacks
 * run one at a time per session, but different sessions may call the same handler concurrently. A
 * callback that throws is reported to {@link #onError} for that session and does not close it, so a
 * handler can decide whether the failure is fatal; a failure of {@code onError} is logged by the
 * transport. Close is delivered exactly once per session, including when the listener shuts down.
 */
public interface WebSocketHandler {
  /**
   * Called once after the handshake succeeds, before any message.
   *
   * @param session the established session
   */
  default void onConnect(WebSocketSession session) {}

  /**
   * Called for every complete text or binary message. Fragmented frames are aggregated before this
   * call. Control frames are handled by the transport and are not delivered here.
   *
   * @param session session that received the message
   * @param message immutable message; text or binary
   */
  default void onMessage(WebSocketSession session, WebSocketMessage message) {}

  /**
   * Called once when the session ends, whether by the peer, the application or listener shutdown.
   *
   * @param session session that ended
   * @param code close code, or 1006 when the connection ended without a close frame
   * @param reason close reason, possibly empty
   */
  default void onClose(WebSocketSession session, int code, String reason) {}

  /**
   * Called when a callback fails or the transport rejects a frame. The session remains usable
   * unless the failure came from the peer's framing, in which case the transport closes it
   * afterwards.
   *
   * @param session session that failed
   * @param failure cause of the failure
   */
  default void onError(WebSocketSession session, Throwable failure) {}
}
