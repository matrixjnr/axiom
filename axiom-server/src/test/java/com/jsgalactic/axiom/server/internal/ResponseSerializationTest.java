package com.jsgalactic.axiom.server.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.http.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the transport's response serialization table; a change here is a wire behavior change. */
class ResponseSerializationTest {
    private static final int MIB = 1024 * 1024;

    @Test void pinsTheLimits() {
        assertThat(ResponseSerialization.MAX_BODY_BYTES).isEqualTo(MIB);
        assertThat(ResponseSerialization.MAX_HEADER_BYTES).isEqualTo(8192);
        assertThat(ResponseSerialization.HEADER_OVERHEAD).isEqualTo(4);
    }

    @Test void bodyTypesAndSizes() {
        assertThat(ResponseSerialization.bodyBytes(null)).isEmpty();
        var bytes = new byte[MIB];
        assertThat(ResponseSerialization.bodyBytes(bytes)).isSameAs(bytes);
        assertThat(ResponseSerialization.bodyBytes(new byte[MIB + 1])).isNull();
        assertThat(ResponseSerialization.bodyBytes("hé")).containsExactly('h', 0xc3, 0xa9);
        assertThat(ResponseSerialization.bodyBytes("x".repeat(MIB))).hasSize(MIB);
        assertThat(ResponseSerialization.bodyBytes("x".repeat(MIB + 1))).isNull();
        // The size limit applies to encoded bytes, not characters.
        assertThat(ResponseSerialization.bodyBytes("é".repeat(MIB / 2))).hasSize(MIB);
        assertThat(ResponseSerialization.bodyBytes("é".repeat(MIB / 2 + 1))).isNull();
        assertThat(ResponseSerialization.bodyBytes(List.of("x"))).isNull();
        assertThat(ResponseSerialization.bodyBytes(new char[] {'x'})).isNull();
    }

    @Test void headerSizeCountsNameValueAndFourPerField() {
        assertThat(ResponseSerialization.headersSendable(Map.of())).isTrue();
        assertThat(ResponseSerialization.headersSendable(Map.of("A", "v".repeat(8192 - 5)))).isTrue();
        assertThat(ResponseSerialization.headersSendable(Map.of("A", "v".repeat(8192 - 4)))).isFalse();
        assertThat(ResponseSerialization.headersSendable(Map.of("A", "v".repeat(4092 - 5), "B", "v".repeat(4100 - 5))))
                .isTrue();
        assertThat(ResponseSerialization.headersSendable(Map.of("A", "v".repeat(4092 - 5), "B", "v".repeat(4100 - 4))))
                .isFalse();
    }

    @Test void contentLengthIsNotCounted() {
        // The transport owns Content-Length; a HEAD response carries one beside headers at the limit.
        var headers = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        headers.put("A", "v".repeat(8192 - 5));
        headers.put("content-length", "1048576");
        assertThat(ResponseSerialization.headersSendable(headers)).isTrue();
    }

    @Test void headerValuesMustBeLatin1() {
        assertThat(ResponseSerialization.headersSendable(Map.of("A", "ÿ"))).isTrue();
        assertThat(ResponseSerialization.headersSendable(Map.of("A", "Ā"))).isFalse();
        assertThat(ResponseSerialization.headersSendable(Map.of("A", "😀"))).isFalse();
    }

    @Test void rejectionNamesTheBrokenRule() {
        assertThat(ResponseSerialization.rejection(Response.of(200, "ok"))).isNull();
        assertThat(ResponseSerialization.rejection(Response.of(204, null))).isNull();
        assertThat(ResponseSerialization.rejection(Response.of(200, List.of("x")))).contains("cannot be serialized");
        assertThat(ResponseSerialization.rejection(Response.of(200, new byte[MIB + 1]))).contains("exceeds");
        assertThat(ResponseSerialization.rejection(Response.of(200, "x").withHeader("A", "Ā")))
                .contains("Latin-1");
    }
}
