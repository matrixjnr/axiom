package com.jsgalactic.axiom.websocket;

import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * One established WebSocket connection.
 *
 * <p>A session belongs to one connection and is owned by the transport that created it. Calls are
 * safe from any thread; {@link #send}, {@link #ping} and {@link #close} throw {@link
 * IllegalStateException} once the session has ended, so a handler must not retain a session beyond
 * {@link WebSocketHandler#onClose}. Writes from a handler may be buffered: a peer that stops
 * reading makes the transport stop reading frames until its writes drain, and a connection that
 * stays stalled is subject to the listener's idle and response timeouts. {@link #request()} and
 * {@link #execution()} give the immutable metadata of the GET that opened this session; they remain
 * readable after close.
 */
public interface WebSocketSession {
  /**
   * Sends a text or binary message, preserving its kind.
   *
   * @param message message to send; copied by the transport
   * @throws IllegalStateException if the session is closed
   */
  void send(WebSocketMessage message);

  /**
   * Sends a text message.
   *
   * @param text text to send
   * @throws IllegalStateException if the session is closed
   */
  void sendText(String text);

  /**
   * Sends a binary message from a copy of the supplied bytes.
   *
   * @param bytes bytes to send; copied
   * @throws IllegalStateException if the session is closed
   */
  void sendBinary(byte[] bytes);

  /**
   * Sends a ping with an application payload. A peer that answers with a pong does not invoke
   * {@link WebSocketHandler#onMessage}; pongs are handled by the transport.
   *
   * @param bytes ping payload; copied
   * @throws IllegalStateException if the session is closed
   */
  void ping(byte[] bytes);

  /**
   * Closes the session normally, with code 1000 and an empty reason. The close callback runs once,
   * whether the peer, the application or the listener ended the session.
   */
  void close();

  /**
   * Closes the session with a protocol status code and a reason.
   *
   * @param code a valid RFC 6455 close code; 1005 and 1006 are reserved and rejected
   * @param reason reason text, at most 123 UTF-8 bytes; may be empty
   * @throws IllegalArgumentException if the code is reserved or the reason is too long
   * @throws IllegalStateException if the session is closed
   */
  void close(int code, String reason);

  /**
   * Returns whether the session can still send and receive.
   *
   * @return true until the close callback begins
   */
  boolean isOpen();

  /**
   * Returns the remote socket address when the transport has one. An in-memory test session has no
   * socket and returns empty.
   *
   * @return peer address, if available
   */
  Optional<InetSocketAddress> remoteAddress();

  /**
   * Returns the immutable handshake request. Its headers include the upgrade fields the peer sent.
   *
   * @return request that opened the session
   */
  Request request();

  /**
   * Returns the immutable identity metadata of the handshake execution.
   *
   * @return execution context of the handshake
   */
  ExecutionContext execution();
}
