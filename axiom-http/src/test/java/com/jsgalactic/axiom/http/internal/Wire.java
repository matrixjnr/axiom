package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.lifecycle.Server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/** A raw socket client that writes request bytes and parses HTTP/1.1 responses. */
final class Wire implements AutoCloseable {
    final Socket socket = new Socket();
    Wire(Server server) throws IOException {
        socket.connect(server.localAddress(), 30_000);
        socket.setSoTimeout(30_000);
    }
    void write(String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
    Reply get(String path) throws IOException {
        write("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
        return read(false);
    }
    Reply read(boolean head) throws IOException {
        String status = line();
        if (!status.startsWith("HTTP/1.1 ")) { throw new IOException("Unexpected status: " + status); }
        int code = Integer.parseInt(status.split(" ")[1]);
        var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (String line; !(line = line()).isEmpty();) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon), line.substring(colon + 1).trim());
        }
        int length = head ? 0 : Integer.parseInt(headers.getOrDefault("Content-Length", "0"));
        var bytes = socket.getInputStream().readNBytes(length);
        if (bytes.length != length) { throw new IOException("Truncated body"); }
        return new Reply(code, headers, bytes);
    }
    String line() throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int value; (value = socket.getInputStream().read()) != -1;) {
            if (value == '\n') { return bytes.toString(StandardCharsets.US_ASCII).replace("\r", ""); }
            bytes.write(value);
            if (bytes.size() > 16384) { throw new IOException("Unbounded line"); }
        }
        throw new IOException("Unexpected EOF");
    }
    @Override public void close() throws IOException { socket.close(); }
}
