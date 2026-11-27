package com.jsgalactic.axiom.http;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.error.BadRequestException;
import com.jsgalactic.axiom.error.MethodNotAllowedException;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.error.PayloadTooLargeException;
import com.jsgalactic.axiom.internal.PercentDecoding;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opt-in static file serving as an ordinary handler, with no dependency beyond the JDK. Register
 * the returned handler on a route with a terminal wildcard and pass its capture name to the
 * builder:
 *
 * <pre>{@code
 * app.get("/assets/*path", StaticFiles.directory(Path.of("public")).build());
 * app.get("/ui/*path", StaticFiles.classpath("web/ui").maxFileSize(8L * 1024 * 1024).build());
 * }</pre>
 *
 * <p>The handler serves only GET and HEAD. It reads the raw capture, refuses every path that the
 * router's raw-path policy refuses (empty and dot segments, backslashes, NUL and controls,
 * malformed escapes and encoded dot, slash, backslash or NUL), percent-decodes each remaining
 * segment once as strict UTF-8, and only then resolves it against the configured root.
 * Normalization follows validation: a candidate must stay under the root after lexical
 * normalization and after symbolic links are resolved. Directories, missing files and targets
 * outside the root answer 404; an unsafe path answers 400 and an oversized file answers 413. These
 * problem responses carry fixed codes and never include the path or a file system detail.
 *
 * <p>Every response carries a fixed {@code Cache-Control}, a strong SHA-256 {@code ETag} computed
 * over the representation, {@code Last-Modified} when the source supplies a time, {@code
 * Accept-Ranges: bytes}, and a {@code Content-Type} from a bounded extension table (falling back to
 * {@code application/octet-stream}). HEAD sends no body and carries the representation length in
 * {@code Content-Length}. GET streams the file through {@link Response#stream}, in bounded pieces,
 * so memory use does not grow with file size; the stream is cancelled with the request. Registering
 * {@link HttpPreconditions#conditional()} gives these responses conditional and range handling,
 * including 304, 412, 206 and 416, without buffering them.
 *
 * <p>The handler is immutable and thread-safe; one instance may serve every route and request. Each
 * request resolves and hashes its own file, so a file replaced while it is being served may be
 * hashed from one version and streamed from another. Applications that require a consistent
 * snapshot must publish immutable files.
 */
public final class StaticFiles {
  /** Default largest served file: 64 MiB, the same bound as a default response stream. */
  public static final long DEFAULT_MAX_FILE_SIZE = 64L * 1024 * 1024;

  /** Default cache policy for served files: publicly cacheable for one hour. */
  public static final String DEFAULT_CACHE_CONTROL = "public, max-age=3600";

  /** Header used when an extension is not in the built-in table. */
  public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

  private static final int BUFFER_SIZE = 16 * 1024;
  private static final long STREAM_OVERHEAD = 4096;
  private static final long MAX_FILE_SIZE_CEILING = 1024L * 1024 * 1024 * 1024;
  private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;
  private static final Pattern CAPTURE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,16}");
  private static final Pattern MEDIA_TYPE =
      Pattern.compile(
          "[A-Za-z0-9!#$%&'*+.^_`|~-]+/[A-Za-z0-9!#$%&'*+.^_`|~-]+(?:;[A-Za-z0-9= ;.,+_-]+)?");
  private static final Map<String, String> MIME_TYPES =
      Map.ofEntries(
          Map.entry("html", "text/html; charset=utf-8"),
          Map.entry("htm", "text/html; charset=utf-8"),
          Map.entry("css", "text/css; charset=utf-8"),
          Map.entry("js", "text/javascript; charset=utf-8"),
          Map.entry("mjs", "text/javascript; charset=utf-8"),
          Map.entry("json", "application/json"),
          Map.entry("xml", "application/xml"),
          Map.entry("svg", "image/svg+xml"),
          Map.entry("png", "image/png"),
          Map.entry("jpg", "image/jpeg"),
          Map.entry("jpeg", "image/jpeg"),
          Map.entry("gif", "image/gif"),
          Map.entry("webp", "image/webp"),
          Map.entry("avif", "image/avif"),
          Map.entry("ico", "image/x-icon"),
          Map.entry("txt", "text/plain; charset=utf-8"),
          Map.entry("md", "text/markdown; charset=utf-8"),
          Map.entry("csv", "text/csv; charset=utf-8"),
          Map.entry("pdf", "application/pdf"),
          Map.entry("wasm", "application/wasm"),
          Map.entry("woff", "font/woff"),
          Map.entry("woff2", "font/woff2"),
          Map.entry("ttf", "font/ttf"),
          Map.entry("otf", "font/otf"),
          Map.entry("mp3", "audio/mpeg"),
          Map.entry("mp4", "video/mp4"),
          Map.entry("webm", "video/webm"),
          Map.entry("zip", "application/zip"),
          Map.entry("gz", "application/gzip"),
          Map.entry("yaml", "application/yaml"),
          Map.entry("yml", "application/yaml"));

  private final Source source;
  private final String capture;
  private final long maxFileSize;
  private final String cacheControl;
  private final Map<String, String> contentTypes;

  private StaticFiles(Builder builder) {
    source = builder.source;
    capture = builder.capture;
    maxFileSize = builder.maxFileSize;
    cacheControl = builder.cacheControl;
    contentTypes = builder.contentTypes;
  }

  /**
   * Configures serving from a directory. The root is used as given; symbolic links are accepted
   * only while their resolved target stays under it.
   *
   * @param root directory that owns every served file
   * @return builder for the handler
   */
  public static Builder directory(Path root) {
    Objects.requireNonNull(root, "root");
    return new Builder(new Directory(root.toAbsolutePath().normalize()));
  }

  /**
   * Configures serving from a classpath location, using the class loader of this class.
   *
   * @param prefix package-style prefix such as {@code web/ui}, or empty for the classpath root
   * @return builder for the handler
   */
  public static Builder classpath(String prefix) {
    return classpath(prefix, StaticFiles.class.getClassLoader());
  }

  /**
   * Configures serving from a classpath location. Entries inside JARs have no file system paths, so
   * there are no symbolic links to contain; directory entries on disk are contained in the same way
   * as a directory root.
   *
   * @param prefix package-style prefix such as {@code web/ui}, or empty for the classpath root
   * @param loader class loader that resolves the prefix
   * @return builder for the handler
   */
  public static Builder classpath(String prefix, ClassLoader loader) {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(loader, "loader");
    String normalized = prefix.startsWith("/") ? prefix.substring(1) : prefix;
    if (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (!normalized.isEmpty()) {
      for (var segment : normalized.split("/", -1)) {
        if (segment.isEmpty()
            || segment.equals(".")
            || segment.equals("..")
            || segment.indexOf('\\') >= 0
            || segment.indexOf('\0') >= 0) {
          throw new IllegalArgumentException("Invalid classpath prefix");
        }
      }
    }
    return new Builder(new Classpath(normalized, loader));
  }

  /** Collects the immutable handler's configuration. Not shared until {@link #build}. */
  public static final class Builder {
    private final Source source;
    private String capture = "path";
    private long maxFileSize = DEFAULT_MAX_FILE_SIZE;
    private String cacheControl = DEFAULT_CACHE_CONTROL;
    private Map<String, String> contentTypes = Map.of();

    private Builder(Source source) {
      this.source = source;
    }

    /**
     * Sets the wildcard capture that holds the file path, {@code path} by default.
     *
     * @param name capture name declared by the route template
     * @return this builder
     * @throws IllegalArgumentException if the name is not a capture identifier
     */
    public Builder capture(String name) {
      Objects.requireNonNull(name, "name");
      if (!CAPTURE_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("Invalid capture name");
      }
      capture = name;
      return this;
    }

    /**
     * Sets the largest file this handler will serve, 64 MiB by default.
     *
     * @param bytes maximum representation length, positive and at most 1 TiB
     * @return this builder
     * @throws IllegalArgumentException if the bound is outside that range
     */
    public Builder maxFileSize(long bytes) {
      if (bytes <= 0 || bytes > MAX_FILE_SIZE_CEILING) {
        throw new IllegalArgumentException("Maximum file size must be positive and at most 1 TiB");
      }
      maxFileSize = bytes;
      return this;
    }

    /**
     * Sets the {@code Cache-Control} field sent with every file.
     *
     * @param value header value of 1 to 512 visible ASCII characters
     * @return this builder
     * @throws IllegalArgumentException if the value is empty, overlong or contains a control
     */
    public Builder cacheControl(String value) {
      Objects.requireNonNull(value, "value");
      if (value.isEmpty()
          || value.length() > 512
          || value.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
        throw new IllegalArgumentException("Invalid Cache-Control value");
      }
      cacheControl = value;
      return this;
    }

    /**
     * Adds or replaces the content type of one lowercase extension. Overrides take precedence over
     * the built-in table; extensions without an entry use {@link #DEFAULT_CONTENT_TYPE}.
     *
     * @param extension file extension without the dot, 1 to 16 ASCII letters or digits
     * @param contentType exact media type with optional parameters
     * @return this builder
     * @throws IllegalArgumentException if the extension or content type is invalid
     */
    public Builder contentType(String extension, String contentType) {
      Objects.requireNonNull(extension, "extension");
      Objects.requireNonNull(contentType, "contentType");
      String name = extension.toLowerCase(Locale.ROOT);
      if (!EXTENSION.matcher(name).matches()
          || contentType.length() > 200
          || !MEDIA_TYPE.matcher(contentType).matches()) {
        throw new IllegalArgumentException("Invalid extension or content type");
      }
      var copy = new HashMap<>(contentTypes);
      copy.put(name, contentType);
      contentTypes = Map.copyOf(copy);
      return this;
    }

    /**
     * Builds the handler. The result holds no reference to this builder and may be shared.
     *
     * @return immutable handler for GET and HEAD requests
     */
    public Handler build() {
      return new StaticFiles(this)::serve;
    }
  }

  private Response serve(Context context) throws Exception {
    var method = context.method();
    if (!method.equals("GET") && !method.equals("HEAD")) {
      throw new MethodNotAllowedException(Set.of("GET", "HEAD"));
    }
    String relative = decode(context.path(capture));
    if (relative.isEmpty()) {
      throw new NotFoundException("file_not_found");
    }
    Located found = source.locate(relative, maxFileSize);
    if (found == null) {
      throw new NotFoundException("file_not_found");
    }
    if (found.size() > maxFileSize) {
      throw new PayloadTooLargeException("file_too_large");
    }
    String type = contentType(relative);
    // The runtime strips a HEAD response's stream but keeps its headers, so the declared
    // length is what the listener sends; a GET leaves framing to the streaming transport.
    Response response =
        Response.slicedStream(200, type, found.size(), streamLimit(found.size()), found.writer())
            .withHeader("Cache-Control", cacheControl)
            .withHeader("Content-Type", type)
            .withHeader("Accept-Ranges", "bytes");
    if (found.etag() != null) {
      response = response.withHeader("ETag", found.etag());
    }
    if (found.modified() != null) {
      response = response.withHeader("Last-Modified", found.modified());
    }
    return method.equals("HEAD")
        ? response.withHeader("Content-Length", Long.toString(found.size()))
        : response;
  }

  private long streamLimit(long size) {
    return Math.min(maxFileSize, size) + STREAM_OVERHEAD;
  }

  private String contentType(String relative) {
    int slash = relative.lastIndexOf('/');
    int dot = relative.lastIndexOf('.');
    String extension =
        dot > slash && dot < relative.length() - 1
            ? relative.substring(dot + 1).toLowerCase(Locale.ROOT)
            : "";
    var override = contentTypes.get(extension);
    return override != null ? override : MIME_TYPES.getOrDefault(extension, DEFAULT_CONTENT_TYPE);
  }

  /** Validates every character before any decoding or file system access, then decodes once. */
  private static String decode(String raw) {
    if (raw == null) {
      throw invalid();
    }
    if (raw.isEmpty()) {
      return "";
    }
    if (raw.charAt(0) == '/' || raw.charAt(0) == '\\') {
      throw invalid();
    }
    if (raw.endsWith("/")) {
      throw new NotFoundException("file_not_found");
    }
    var decoded = new StringBuilder(raw.length());
    int segmentStart = 0;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c <= 0x20 || c == 0x7f || c == '\\') {
        throw invalid();
      }
      if (c == '%') {
        if (i + 2 >= raw.length()) {
          throw invalid();
        }
        int high = PercentDecoding.hex(raw.charAt(i + 1));
        int low = PercentDecoding.hex(raw.charAt(i + 2));
        if (high < 0 || low < 0) {
          throw invalid();
        }
        int value = high * 16 + low;
        if (value == 0x2e || value == 0x2f || value == 0x5c || value == 0x00) {
          throw invalid();
        }
        i += 2;
      } else if (c == '/' && (i == segmentStart || i + 1 == raw.length())) {
        throw invalid();
      }
      if (c == '/' && i + 1 < raw.length()) {
        segmentStart = i + 1;
      }
    }
    for (var part : raw.split("/", -1)) {
      String segment;
      try {
        segment = PercentDecoding.decode(part, 0, part.length(), false);
      } catch (IllegalArgumentException malformed) {
        throw invalid();
      }
      if (segment.isEmpty()
          || segment.equals(".")
          || segment.equals("..")
          || segment.indexOf('/') >= 0
          || segment.indexOf('\\') >= 0
          || segment.indexOf('\0') >= 0) {
        throw invalid();
      }
      if (decoded.length() > 0) {
        decoded.append('/');
      }
      decoded.append(segment);
    }
    return decoded.toString();
  }

  private static BadRequestException invalid() {
    return new BadRequestException("invalid_path_encoding");
  }

  private sealed interface Source permits Directory, Classpath {
    Located locate(String relative, long max);
  }

  private record Directory(Path root) implements Source {
    @Override
    public Located locate(String relative, long max) {
      Path candidate;
      try {
        candidate = root.resolve(relative).normalize();
      } catch (InvalidPathException malformed) {
        throw invalid();
      }
      if (!candidate.startsWith(root) || candidate.equals(root)) {
        throw invalid();
      }
      try {
        Path realRoot = root.toRealPath();
        Path real = candidate.toRealPath();
        if (!real.startsWith(realRoot) || !Files.isRegularFile(real)) {
          return null;
        }
        long size = Files.size(real);
        if (size > max) {
          return new Located(size, null, null, null);
        }
        return new Located(
            size,
            digest(real),
            httpDate(Files.getLastModifiedTime(real).toInstant()),
            (out, first, last) -> copy(real, out, first, last));
      } catch (IOException | InvalidPathException unavailable) {
        return null;
      }
    }
  }

  private record Classpath(String prefix, ClassLoader loader) implements Source {
    @Override
    public Located locate(String relative, long max) {
      String name = prefix.isEmpty() ? relative : prefix + "/" + relative;
      URL url = loader.getResource(name);
      if (url == null) {
        return null;
      }
      try {
        if (url.getProtocol().equals("file")) {
          return fileResource(url, name, max);
        }
        if (url.getProtocol().equals("jar")) {
          return jarResource(url, max);
        }
        return null;
      } catch (IOException | URISyntaxException | InvalidPathException unavailable) {
        return null;
      }
    }

    private Located fileResource(URL url, String name, long max)
        throws IOException, URISyntaxException {
      Path real = Path.of(url.toURI()).toRealPath();
      URL rootUrl = loader.getResource(prefix);
      if (rootUrl == null || !rootUrl.getProtocol().equals("file")) {
        return null;
      }
      Path root = Path.of(rootUrl.toURI()).toRealPath();
      if (!real.startsWith(root) || !Files.isRegularFile(real)) {
        return null;
      }
      long size = Files.size(real);
      if (size > max) {
        return new Located(size, null, null, null);
      }
      return new Located(
          size,
          digest(real),
          httpDate(Files.getLastModifiedTime(real).toInstant()),
          (out, first, last) -> copy(real, out, first, last));
    }

    private Located jarResource(URL url, long max) throws IOException {
      Digested result = digest(url.openStream(), max);
      if (result.etag() == null) {
        return new Located(result.size(), null, null, null);
      }
      long modified = url.openConnection().getLastModified();
      String date = modified > 0 ? httpDate(Instant.ofEpochMilli(modified)) : null;
      return new Located(
          result.size(), result.etag(), date, (out, first, last) -> copy(url, out, first, last));
    }
  }

  private record Located(long size, String etag, String modified, Response.SliceWriter writer) {}

  private record Digested(long size, String etag) {}

  private static String digest(Path file) throws IOException {
    try {
      var sha = MessageDigest.getInstance("SHA-256");
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
        var buffer = ByteBuffer.allocate(BUFFER_SIZE);
        while (channel.read(buffer) != -1) {
          buffer.flip();
          sha.update(buffer);
          buffer.clear();
        }
      }
      return quote("sha256-" + hex(sha.digest()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
    }
  }

  private static Digested digest(InputStream in, long max) throws IOException {
    try {
      var sha = MessageDigest.getInstance("SHA-256");
      var buffer = new byte[BUFFER_SIZE];
      long size = 0;
      int read;
      while ((read = in.read(buffer)) != -1) {
        size += read;
        if (size > max) {
          return new Digested(size, null);
        }
        sha.update(buffer, 0, read);
      }
      return new Digested(size, quote("sha256-" + hex(sha.digest())));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
    } finally {
      in.close();
    }
  }

  private static void copy(Path file, BodyWriter out, long first, long last) throws IOException {
    if (last < first) {
      return;
    }
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      channel.position(first);
      var buffer = ByteBuffer.allocate(BUFFER_SIZE);
      long remaining = last - first + 1;
      while (remaining > 0) {
        buffer.clear();
        buffer.limit((int) Math.min(buffer.capacity(), remaining));
        int read = channel.read(buffer);
        if (read < 0) {
          return;
        }
        buffer.flip();
        out.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        remaining -= read;
      }
    }
  }

  private static void copy(URL url, BodyWriter out, long first, long last) throws IOException {
    if (last < first) {
      return;
    }
    try (InputStream in = url.openStream()) {
      var buffer = new byte[BUFFER_SIZE];
      long skip = first;
      while (skip > 0) {
        int read = in.read(buffer, 0, (int) Math.min(buffer.length, skip));
        if (read < 0) {
          return;
        }
        skip -= read;
      }
      long remaining = last - first + 1;
      while (remaining > 0) {
        int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
        if (read < 0) {
          return;
        }
        out.write(buffer, 0, read);
        remaining -= read;
      }
    }
  }

  private static String httpDate(Instant instant) {
    return HTTP_DATE.format(
        Instant.ofEpochSecond(instant.getEpochSecond()).atOffset(ZoneOffset.UTC));
  }

  private static String quote(String value) {
    return '"' + value + '"';
  }

  private static String hex(byte[] bytes) {
    var result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(Character.forDigit(value >> 4 & 0xf, 16));
      result.append(Character.forDigit(value & 0xf, 16));
    }
    return result.toString();
  }
}
