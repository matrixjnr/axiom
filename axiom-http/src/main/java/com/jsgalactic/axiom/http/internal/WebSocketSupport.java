package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.websocket.WebSocketMessage;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Shared frame bounds and decoding for the server's WebSocket transport. All Netty types stay here.
 */
final class WebSocketSupport {
  /** Largest payload of one frame and of one aggregated text or binary message: 64 KiB. */
  static final int MAX_FRAME = 64 * 1024;

  /**
   * Outstanding writes before reads are paused; bounds memory held for a peer that stops reading.
   */
  static final int MAX_PENDING_WRITES = 16;

  private WebSocketSupport() {}

  /**
   * Decoder settings that bound frames, require masked client frames and reject protocol errors.
   */
  static WebSocketDecoderConfig decoderConfig() {
    return WebSocketDecoderConfig.newBuilder()
        .maxFramePayloadLength(MAX_FRAME)
        .expectMaskedFrames(true)
        .allowMaskMismatch(false)
        .allowExtensions(false)
        // The session writes the close frame itself: the decoder is before the frame encoder,
        // so a close frame written by Netty here would never be encoded.
        .closeOnProtocolViolation(false)
        .withUTF8Validator(true)
        .build();
  }

  /** Converts a data frame into an immutable message, rejecting invalid UTF-8 text. */
  static WebSocketMessage message(WebSocketFrame frame, ByteBuf bytes)
      throws CorruptedWebSocketFrameException {
    if (frame instanceof BinaryWebSocketFrame) {
      var copy = new byte[bytes.readableBytes()];
      bytes.getBytes(bytes.readerIndex(), copy);
      return WebSocketMessage.binary(copy);
    }
    var decoder = StandardCharsets.UTF_8.newDecoder();
    decoder.onMalformedInput(CodingErrorAction.REPORT);
    decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      var text = decoder.decode(bytes.nioBuffer()).toString();
      return WebSocketMessage.text(text);
    } catch (CharacterCodingException invalid) {
      throw new CorruptedWebSocketFrameException(
          WebSocketCloseStatus.INVALID_PAYLOAD_DATA, "Invalid UTF-8 text frame", invalid);
    }
  }
}
