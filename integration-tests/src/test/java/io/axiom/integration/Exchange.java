package io.axiom.integration;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** One request and its response, independent of whether it went through TestClient or a socket. */
final class Exchange {
    private Exchange() { }

    /** A response with case-insensitive header names and the body decoded as UTF-8. */
    record Reply(int status, Map<String, String> headers, String body) {
        Reply {
            var sorted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            sorted.putAll(headers);
            headers = sorted;
        }

        static Reply of(int status, Map<String, String> headers, byte[] body) {
            return new Reply(status, headers, new String(body, StandardCharsets.UTF_8));
        }

        String contentType() { return headers.getOrDefault("Content-Type", ""); }
    }

    /** Sends requests to an application. */
    interface Client {
        /**
         * Sends a request.
         *
         * @param method HTTP method
         * @param path request path
         * @param headers request headers, including Content-Type and Accept when wanted
         * @param body request content, possibly empty
         * @return response
         */
        Reply send(String method, String path, Map<String, String> headers, byte[] body) throws Exception;

        /**
         * Sends a request that declares a body of the given length. A listener receives only the
         * head, so the answer cannot depend on reading the body; in memory the body is real.
         *
         * @param path request path
         * @param contentType Content-Type header value
         * @param length declared body length
         * @return response
         */
        Reply sendOversized(String path, String contentType, int length) throws Exception;

        /** Closes the application and waits for its listener, if any, to terminate. */
        void close() throws Exception;
    }
}
