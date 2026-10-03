package com.jsgalactic.axiom.http.internal;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** A parsed response. */
record Reply(int status, Map<String, String> headers, byte[] body) {
    String text() { return new String(body, StandardCharsets.UTF_8); }
}
