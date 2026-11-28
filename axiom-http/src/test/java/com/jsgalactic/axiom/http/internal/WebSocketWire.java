package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.lifecycle.Server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.TreeMap;

/** A raw RFC 6455 client that writes masked frames and parses unmasked server frames. */
final class WebSocketWire implements AutoCloseable {
  private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";
  private static final String ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

  private final Socket socket = new Socket();
  private final InputStream in;
  private final OutputStream out;

  WebSocketWire(Server server) throws IOException {
    this(server, 0);
  }

  /** A client whose small receive buffer makes it a slow reader. */
  WebSocketWire(Server server, int receiveBuffer) throws IOException {
    if (receiveBuffer > 0) {
      socket.setReceiveBufferSize(receiveBuffer);
    }
    socket.connect(server.localAddress(), 30_000);
    socket.setSoTimeout(30_000);
    in = socket.getInputStream();
    out = socket.getOutputStream();
  }

  /** Sends a version 13 handshake with the fixed key and returns the parsed response headers. */
  TreeMap<String, String> handshake(String target) throws IOException {
    return handshake(target, KEY, "13", true);
  }

  /** Sends a handshake with supplied key and version, optionally with the Connection field. */
  TreeMap<String, String> handshake(
      String target, String key, String version, boolean connectionUpgrade) throws IOException {
    var request =
        "GET "
            + target
            + " HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
            + (connectionUpgrade ? "Connection: Upgrade\r\n" : "")
            + "Sec-WebSocket-Key: "
            + key
            + "\r\nSec-WebSocket-Version: "
            + version
            + "\r\n\r\n";
    out.write(request.getBytes(StandardCharsets.US_ASCII));
    out.flush();
    return response();
  }

  /** Reads one HTTP response head, leaving any body unread. */
  TreeMap<String, String> response() throws IOException {
    var status = line();
    var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    for (var next = line(); !next.isEmpty(); next = line()) {
      int colon = next.indexOf(':');
      headers.put(next.substring(0, colon), next.substring(colon + 1).trim());
    }
    if (!status.startsWith("HTTP/1.1 ")) {
      throw new IOException("Unexpected status: " + status);
    }
    headers.put(":status", status.split(" ")[1]);
    return headers;
  }

  /** Writes one client frame with the given opcode and a mandatory mask. */
  void frame(boolean fin, int opcode, byte[] payload) throws IOException {
    int length = payload.length;
    var header = new ByteArrayOutputStream();
    header.write(fin ? 0x80 | opcode : opcode);
    if (length < 126) {
      header.write(0x80 | length);
    } else if (length <= 0xFFFF) {
      header.write(0x80 | 126);
      header.write(length >>> 8);
      header.write(length);
    } else {
      header.write(0x80 | 127);
      for (int shift = 56; shift >= 0; shift -= 8) {
        header.write(length >>> shift);
      }
    }
    var mask = new byte[] {1, 2, 3, 4};
    header.write(mask);
    for (int i = 0; i < length; i++) {
      header.write(payload[i] ^ mask[i % 4]);
    }
    out.write(header.toByteArray());
    out.flush();
  }

  /** Reads one server frame. */
  Frame read() throws IOException {
    int first = in.read();
    int second = in.read();
    if (first < 0 || second < 0) {
      throw new IOException("Connection ended inside a frame");
    }
    long length = second & 0x7F;
    if (length == 126) {
      length = ((long) in.read() << 8) | in.read();
    } else if (length == 127) {
      length = 0;
      for (int i = 0; i < 8; i++) {
        length = (length << 8) | in.read();
      }
    }
    if ((second & 0x80) != 0) {
      throw new IOException("Server frame was masked");
    }
    var payload = in.readNBytes((int) length);
    if (payload.length != length) {
      throw new IOException("Truncated frame");
    }
    return new Frame((first & 0x80) != 0, first & 0x0F, payload);
  }

  private String line() throws IOException {
    var bytes = new ByteArrayOutputStream();
    for (int value; (value = in.read()) != -1; ) {
      if (value == '\n') {
        return bytes.toString(StandardCharsets.US_ASCII).replace("\r", "");
      }
      bytes.write(value);
      if (bytes.size() > 16384) {
        throw new IOException("Unbounded line");
      }
    }
    throw new IOException("Unexpected EOF");
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  /** The fixed key's expected Sec-WebSocket-Accept value, for assertions in tests. */
  static String accept() {
    return ACCEPT;
  }

  /** The fixed key sent by {@link #handshake(String)}. */
  static String key() {
    return KEY;
  }

  /** Decodes the fixed key's nonce; tests use it to check the handshake preserved it. */
  static byte[] keyBytes() {
    return Base64.getDecoder().decode(KEY);
  }

  /** One parsed server frame. */
  record Frame(boolean fin, int opcode, byte[] payload) {
    /** Decodes a text frame's UTF-8 payload. */
    String text() {
      return new String(payload, StandardCharsets.UTF_8);
    }

    /** Reads the two-byte close code, or -1 when the frame carries no code. */
    int code() {
      return payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : -1;
    }
  }
}
