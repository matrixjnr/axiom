package com.jsgalactic.axiom.metrics;

import java.util.Map;

/** Help text of the metrics the Axiom runtime records itself; see the observability guide. */
final class RuntimeHelp {
    private static final Map<String, String> TEXT = Map.ofEntries(
            Map.entry("axiom.http.requests", "Finished requests by route template and status class."),
            Map.entry("axiom.http.request.duration", "Time from submission to the final outcome, including queue wait."),
            Map.entry("axiom.admission.rejected", "Requests refused or expired by admission, by reason."),
            Map.entry("axiom.admission.queue.wait", "Time requests spent queued before execution."),
            Map.entry("axiom.admission.active", "Reserved or running executions."),
            Map.entry("axiom.admission.queued", "Requests waiting for capacity."),
            Map.entry("axiom.http.connections", "Open client connections by listener and state."),
            Map.entry("axiom.http.connections.accepted", "Connections accepted, by listener."),
            Map.entry("axiom.http.connections.rejected", "Connections closed at once, by listener and reason."),
            Map.entry("axiom.http.listener.bytes", "Bytes read from and written to client sockets, by listener and direction."),
            Map.entry("axiom.http.listener.answers", "Responses a listener gave without admission, by listener and status."),
            Map.entry("axiom.codec.duration", "Time spent encoding and decoding bodies, by operation and media type."),
            Map.entry("axiom.codec.failures", "Encodings and decodings that failed, by operation and media type."),
            Map.entry("axiom.http.streams", "Finished streamed responses by outcome."),
            Map.entry("axiom.http.stream.bytes", "Body bytes written by streamed responses."),
            Map.entry("axiom.http.streams.active", "Streamed responses whose body is running."),
            Map.entry("axiom.http.stream.backpressure", "Stream writes that waited for a slow client."),
            Map.entry("axiom.http.tls.handshakes", "TLS handshakes by outcome."),
            Map.entry("axiom.http.tls.reloads", "TLS key material reloads by outcome."));

    private RuntimeHelp() { }

    /** The help text of a runtime metric, or null for any other name. */
    static String of(String name) { return TEXT.get(name); }
}
