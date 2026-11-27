package com.jsgalactic.axiom.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Event-based parser for {@code multipart/*} request bodies read from a {@link BodyReader}. The
 * parser drives the reader itself: {@link #parse()} blocks until the closing boundary or a failure
 * and calls {@link Listener#part} once for each complete part, in wire order. It never buffers the
 * whole body. Part content is held in memory up to a small threshold and then spilled to a
 * temporary file; every file is deleted by {@link #close()}, including after a parse failure or
 * when the handler stops reading early. Part values are valid only while the parser is open.
 *
 * <p>All limits are explicit in {@link Limits} and are checked while bytes arrive: the number of
 * parts, bytes per part, bytes of each part's header section and total body bytes, including
 * boundaries and preamble. An exceeded limit or malformed framing throws {@link
 * MultipartException}, which a handler can let propagate as a 400. The caller owns the source
 * reader and should close it separately.
 */
public final class MultipartParser implements AutoCloseable {
  /** Bounds applied while a multipart body is read. */
  public record Limits(int maxParts, int maxPartBytes, int maxHeaderBytes, long maxTotalBytes) {
    /** Sixteen parts of at most 1 MiB, 8 KiB of headers per part and 16 MiB in total. */
    public static final Limits DEFAULT = new Limits(16, 1024 * 1024, 8192, 16L * 1024 * 1024);

    /**
     * Validates and creates limits; every bound must be positive.
     *
     * @param maxParts largest number of parts
     * @param maxPartBytes largest content of one part
     * @param maxHeaderBytes largest header section of one part, including line ends
     * @param maxTotalBytes largest number of body bytes read, including framing
     * @throws IllegalArgumentException if any bound is not positive
     */
    public Limits {
      if (maxParts <= 0 || maxPartBytes <= 0 || maxHeaderBytes <= 0 || maxTotalBytes <= 0) {
        throw new IllegalArgumentException("Multipart limits must be positive");
      }
    }
  }

  /** Receives parts as they are completed by the parser. */
  public interface Listener {
    /**
     * Receives one complete part. The part's bytes remain readable until the parser is closed.
     *
     * @param part part with its headers and content
     * @throws IOException to stop parsing and abandon the body
     */
    void part(Part part) throws IOException;

    /**
     * Called once after the closing boundary, before any temporary file is deleted.
     *
     * @throws IOException to abandon the body
     */
    default void complete() throws IOException {}
  }

  /** One complete part: its headers, disposition parameters and bounded content. */
  public record Part(
      Map<String, String> headers,
      String name,
      String filename,
      String contentType,
      long size,
      byte[] bytes) {
    /**
     * Validates and copies the part's values.
     *
     * @param headers case-insensitive header map
     * @param name form field name, or null
     * @param filename file name, or null
     * @param contentType part Content-Type, or null
     * @param size content length in bytes
     * @param bytes content, owned by the part
     */
    public Part {
      var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
      copy.putAll(headers);
      headers = Collections.unmodifiableMap(copy);
      bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    /**
     * Decodes the content as UTF-8.
     *
     * @return text of the part
     */
    public String text() {
      return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Decodes the content with a charset.
     *
     * @param charset charset to use
     * @return text of the part
     */
    public String text(Charset charset) {
      return new String(bytes, Objects.requireNonNull(charset, "charset"));
    }
  }

  private static final int SPILL_THRESHOLD = 8192;
  private final BodyReader source;
  private final byte[] boundary;
  private final byte[] delimiter;
  private final Limits limits;
  private final Listener listener;
  private final Path directory;
  private final List<PartBuffer> buffers = new ArrayList<>();
  private final Source stream;
  private boolean closed;
  private boolean empty;

  /**
   * Creates a parser that uses the system temporary directory for spilled parts.
   *
   * @param source reader of the body; owned by the caller
   * @param contentType request or part Content-Type containing the boundary parameter
   * @param limits explicit bounds
   * @param listener receives completed parts
   * @throws MultipartException if the media type or boundary is missing or malformed
   */
  public MultipartParser(BodyReader source, String contentType, Limits limits, Listener listener) {
    this(source, contentType, limits, listener, null);
  }

  /**
   * Creates a parser that spills parts into the given directory, so an application or a test can
   * choose and observe the temporary storage.
   *
   * @param source reader of the body; owned by the caller
   * @param contentType request Content-Type containing the boundary parameter
   * @param limits explicit bounds
   * @param listener receives completed parts
   * @param directory existing directory for temporary files, or null for the system directory
   * @throws MultipartException if the media type or boundary is missing or malformed
   * @throws IllegalArgumentException if the directory does not exist or is not writable
   */
  public MultipartParser(
      BodyReader source, String contentType, Limits limits, Listener listener, Path directory) {
    this.source = Objects.requireNonNull(source, "source");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.listener = Objects.requireNonNull(listener, "listener");
    if (directory != null && (!Files.isDirectory(directory) || !Files.isWritable(directory))) {
      throw new IllegalArgumentException("Temp directory must exist and be writable");
    }
    this.directory = directory;
    var value = boundary(contentType);
    this.boundary = value.getBytes(StandardCharsets.US_ASCII);
    this.delimiter = ("\r\n--" + value).getBytes(StandardCharsets.US_ASCII);
    this.stream = new Source();
  }

  /**
   * Reads the body to its closing boundary, delivering each complete part to the listener. Calling
   * it twice, or calling it after {@link #close()}, fails.
   *
   * @throws IOException if the source fails, the listener fails or a limit is exceeded
   * @throws MultipartException if the framing is malformed
   * @throws IllegalStateException if the parser was used already
   */
  public void parse() throws IOException {
    if (closed) {
      throw new IllegalStateException("Parser is closed");
    }
    stream.started = true;
    if (!seekFirstBoundary()) {
      throw malformed();
    }
    if (empty) {
      listener.complete();
      return;
    }
    while (true) {
      int suffix;
      do {
        suffix = readPart();
      } while (suffix < 0); // A boundary-like sequence that is not a delimiter is part data.
      if (suffix == 2) {
        listener.complete();
        return;
      }
      if (suffix != 0) {
        throw malformed();
      }
    }
  }

  /**
   * Deletes all temporary part files. Idempotent and safe to call on a failed parse or from a
   * handler that stopped reading early. The source reader is not closed.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    for (var buffer : buffers) {
      buffer.delete();
    }
    buffers.clear();
  }

  /** Finds the first boundary, skipping a bounded preamble. */
  private boolean seekFirstBoundary() throws IOException {
    while (true) {
      var line = stream.line(limits.maxTotalBytes());
      if (line == null) {
        return false;
      }
      if (line.equals("--" + new String(boundary, StandardCharsets.US_ASCII))) {
        return true;
      }
      if (line.equals("--" + new String(boundary, StandardCharsets.US_ASCII) + "--")) {
        empty = true;
        return true;
      }
    }
  }

  /** Returns 0 for CRLF after a boundary, 2 for the closing {@code --}, or -1 for anything else. */
  private int boundarySuffix() throws IOException {
    stream.spaces();
    int first = stream.next();
    if (first == '\r') {
      int second = stream.next();
      if (second == '\n') {
        return 0;
      }
      if (second >= 0) {
        stream.push(new byte[] {(byte) first, (byte) second});
      }
      return -1;
    }
    if (first == '-') {
      int second = stream.next();
      if (second == '-') {
        return 2;
      }
      if (second >= 0) {
        stream.push(new byte[] {(byte) first, (byte) second});
      }
      return -1;
    }
    if (first >= 0) {
      stream.push(new byte[] {(byte) first});
    }
    return -1;
  }

  /**
   * Reads one part's headers and content, then emits it.
   *
   * @return 0 when another part follows, 2 for the closing boundary, or -1 for a boundary-like
   *     sequence that was part data
   */
  private int readPart() throws IOException {
    if (buffers.size() >= limits.maxParts()) {
      throw limit();
    }
    var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    int headerBytes = 0;
    while (true) {
      var line = stream.line(limits.maxHeaderBytes());
      if (line == null) {
        throw malformed();
      }
      headerBytes += line.length() + 2;
      if (headerBytes > limits.maxHeaderBytes()) {
        throw limit();
      }
      if (line.isEmpty()) {
        break;
      }
      int colon = line.indexOf(':');
      if (colon <= 0 || !token(line.substring(0, colon))) {
        throw malformed();
      }
      headers.put(
          line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
    }
    var buffer = new PartBuffer(directory);
    buffers.add(buffer);
    var pending = new ByteArrayOutputStream(delimiter.length + 8);
    while (true) {
      int value = stream.next();
      if (value < 0) {
        throw malformed();
      }
      pending.write(value);
      if (endsWithDelimiter(pending)) {
        int suffix = boundarySuffix();
        if (suffix >= 0) {
          int keep = pending.size() - delimiter.length;
          if (buffer.size() + keep > limits.maxPartBytes()) {
            throw limit();
          }
          buffer.write(pending.toByteArray(), 0, keep);
          String disposition = headers.getOrDefault("content-disposition", "");
          var part =
              new Part(
                  headers,
                  parameter(disposition, "name"),
                  parameter(disposition, "filename"),
                  headers.get("content-type"),
                  buffer.size(),
                  buffer.bytes());
          listener.part(part);
          return suffix;
        }
      }
      if (pending.size() > delimiter.length + 4) {
        if (buffer.size() + 1 > limits.maxPartBytes()) {
          throw limit();
        }
        buffer.write(pending.toByteArray(), 0, 1);
        byte[] kept = pending.toByteArray();
        pending.reset();
        pending.write(kept, 1, kept.length - 1);
      }
    }
  }

  /** Reports whether the pending bytes end with CRLF and the boundary. */
  private boolean endsWithDelimiter(ByteArrayOutputStream pending) {
    byte[] bytes = pending.toByteArray();
    if (bytes.length < delimiter.length) {
      return false;
    }
    int start = bytes.length - delimiter.length;
    for (int i = 0; i < delimiter.length; i++) {
      if (bytes[start + i] != delimiter[i]) {
        return false;
      }
    }
    return true;
  }

  /** Reads a quoted or token parameter, returning null when absent. */
  private static String parameter(String header, String name) {
    String needle = name.toLowerCase(Locale.ROOT) + "=";
    String lower = header.toLowerCase(Locale.ROOT);
    int at = 0;
    while ((at = lower.indexOf(needle, at)) >= 0) {
      int start = at + needle.length();
      while (start < header.length() && header.charAt(start) == ' ') {
        start++;
      }
      if (start < header.length() && header.charAt(start) == '"') {
        int end = header.indexOf('"', start + 1);
        return end < 0 ? null : header.substring(start + 1, end);
      }
      int end = start;
      while (end < header.length() && header.charAt(end) != ';') {
        end++;
      }
      String value = header.substring(start, end).trim();
      if (!value.isEmpty()) {
        return value;
      }
      at = end;
    }
    return null;
  }

  /** Extracts and validates the exclusive boundary parameter of a multipart Content-Type. */
  private static String boundary(String contentType) {
    if (contentType == null) {
      throw malformed();
    }
    var parts = contentType.split(";");
    if (!parts[0].trim().toLowerCase(Locale.ROOT).startsWith("multipart/")) {
      throw malformed();
    }
    for (int i = 1; i < parts.length; i++) {
      var parameter = parts[i].trim();
      int equals = parameter.indexOf('=');
      if (equals < 0 || !parameter.substring(0, equals).trim().equalsIgnoreCase("boundary")) {
        continue;
      }
      String value = parameter.substring(equals + 1).trim();
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      if (value.isEmpty()
          || value.length() > 70
          || !token(value)
          || value.charAt(value.length() - 1) == ' ') {
        throw malformed();
      }
      return value;
    }
    throw malformed();
  }

  private static boolean token(String value) {
    return !value.isEmpty()
        && value
            .chars()
            .allMatch(c -> c <= 32 || c >= 127 ? false : "()<>@,;:\\\"/[]?=".indexOf(c) < 0);
  }

  private static MultipartException malformed() {
    return new MultipartException(MultipartException.MALFORMED);
  }

  private static MultipartException limit() {
    return new MultipartException(MultipartException.LIMIT_EXCEEDED);
  }

  /** Counts every source byte against the total limit and buffers it in small pieces. */
  private final class Source {
    private final byte[] array = new byte[4096];
    private final ArrayDeque<Integer> pushed = new ArrayDeque<>();
    private int position;
    private int length;
    private long total;
    private boolean started;
    private boolean finished;

    /** Returns bytes to the front of the stream, in their original order. */
    private void push(byte[] bytes) {
      for (int i = bytes.length - 1; i >= 0; i--) {
        pushed.addFirst((int) bytes[i] & 0xff);
      }
    }

    private int next() throws IOException {
      if (!pushed.isEmpty()) {
        return pushed.removeFirst();
      }
      if (finished) {
        return -1;
      }
      if (position == length) {
        if (!started) {
          throw new IllegalStateException("Parser has not started");
        }
        length = source.read(array, 0, array.length);
        position = 0;
        if (length <= 0) {
          finished = true;
          return -1;
        }
      }
      if (++total > limits.maxTotalBytes()) {
        throw limit();
      }
      return array[position++] & 0xff;
    }

    /** Reads a CRLF-terminated line without its terminator; null at an unexpected end. */
    private String line(long max) throws IOException {
      var text = new StringBuilder();
      while (true) {
        int value = next();
        if (value < 0) {
          return null;
        }
        if (value == '\n') {
          if (text.isEmpty() || text.charAt(text.length() - 1) != '\r') {
            throw malformed();
          }
          return text.substring(0, text.length() - 1);
        }
        text.append((char) value);
        if (text.length() > max) {
          throw limit();
        }
      }
    }

    /** Consumes optional horizontal whitespace after a boundary. */
    private void spaces() throws IOException {
      while (true) {
        int value = next();
        if (value != ' ' && value != '\t') {
          pushed.addLast(value);
          return;
        }
      }
    }
  }

  /** Bounded part storage: memory first, then one temporary file, always deleted on close. */
  private static final class PartBuffer {
    private final Path directory;
    private ByteArrayOutputStream memory = new ByteArrayOutputStream();
    private Path file;
    private OutputStream out;
    private int size;

    private PartBuffer(Path directory) {
      this.directory = directory;
    }

    private void write(byte[] bytes, int offset, int length) throws IOException {
      size += length;
      if (size > 1024 * 1024 * 1024) {
        throw limit();
      }
      if (file == null && size > SPILL_THRESHOLD) {
        file = Files.createTempFile(directory, "axiom-multipart-", ".tmp");
        out = Files.newOutputStream(file);
        out.write(memory.toByteArray());
        memory = null;
      }
      if (out == null) {
        memory.write(bytes, offset, length);
      } else {
        out.write(bytes, offset, length);
      }
    }

    private int size() {
      return size;
    }

    private byte[] bytes() throws IOException {
      if (out != null) {
        out.flush();
      }
      return file == null ? memory.toByteArray() : Files.readAllBytes(file);
    }

    private void delete() {
      try {
        if (out != null) {
          out.close();
        }
      } catch (IOException ignored) {
        /* Best-effort cleanup. */
      }
      if (file != null) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException ignored) {
          /* Best-effort cleanup. */
        }
      }
    }
  }
}
