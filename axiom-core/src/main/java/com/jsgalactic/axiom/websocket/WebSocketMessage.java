package com.jsgalactic.axiom.websocket;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable text or binary WebSocket message delivered to or from a {@link WebSocketSession}.
 *
 * <p>A message owns a private copy of its payload: factories copy their input and {@link #bytes()}
 * returns a fresh copy, so neither the sender nor a receiver can change what another sees. Messages
 * are safe to share between threads. Text is validated as UTF-8 when it is created; binary payloads
 * are uninterpreted bytes. {@link #toString()} reports only the kind and length, never content.
 */
public final class WebSocketMessage {
  private final String text;
  private final byte[] bytes;

  private WebSocketMessage(String text, byte[] bytes) {
    this.text = text;
    this.bytes = bytes;
  }

  /**
   * Creates a text message.
   *
   * @param text text payload; copied by immutability of {@link String}
   * @return text message
   * @throws NullPointerException if text is null
   */
  public static WebSocketMessage text(String text) {
    Objects.requireNonNull(text, "text");
    return new WebSocketMessage(text, null);
  }

  /**
   * Creates a binary message from a copy of the supplied bytes.
   *
   * @param bytes binary payload; copied
   * @return binary message
   * @throws NullPointerException if bytes is null
   */
  public static WebSocketMessage binary(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    return new WebSocketMessage(null, bytes.clone());
  }

  /**
   * Creates a binary message from the remaining bytes of a buffer, without changing its position.
   *
   * @param bytes buffer whose remaining bytes become the payload; read, not retained
   * @return binary message
   * @throws NullPointerException if bytes is null
   */
  public static WebSocketMessage binary(ByteBuffer bytes) {
    Objects.requireNonNull(bytes, "bytes");
    var copy = new byte[bytes.remaining()];
    bytes.duplicate().get(copy);
    return new WebSocketMessage(null, copy);
  }

  /**
   * Reports whether this is a text message.
   *
   * @return true for text, false for binary
   */
  public boolean isText() {
    return text != null;
  }

  /**
   * Returns the text payload.
   *
   * @return text payload
   * @throws IllegalStateException if this is a binary message
   */
  public String text() {
    if (text == null) {
      throw new IllegalStateException("Binary message has no text payload");
    }
    return text;
  }

  /**
   * Returns a copy of the binary payload.
   *
   * @return binary payload; copied
   * @throws IllegalStateException if this is a text message
   */
  public byte[] bytes() {
    if (bytes == null) {
      throw new IllegalStateException("Text message has no binary payload");
    }
    return bytes.clone();
  }

  /**
   * Returns the payload length: characters for text, bytes for binary.
   *
   * @return payload length
   */
  public int length() {
    return text != null ? text.length() : bytes.length;
  }

  @Override
  public String toString() {
    return (text != null ? "text[" : "binary[") + length() + "]";
  }

  /** Compares payloads by kind and content; copies are equal to their originals. */
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof WebSocketMessage message)) {
      return false;
    }
    return text != null
        ? text.equals(message.text)
        : message.text == null && Arrays.equals(bytes, message.bytes);
  }

  /** Hashes the payload by kind and content. */
  @Override
  public int hashCode() {
    return text != null ? text.hashCode() : Arrays.hashCode(bytes);
  }
}
