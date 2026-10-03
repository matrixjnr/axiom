package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.http.Response;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class HttpQueryTest {
    @Test void deliversDecodedQueryParametersOverTheWire() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/search", ctx -> ctx.path() + "|" + ctx.request().query() + "|"
                    + ctx.query("q").orElse("none") + "|" + ctx.queryAll("tag"));
            fixture.app.post("/echo", ctx -> Response.of(200, ctx.query("id").orElse("none") + "|"
                    + ctx.request().body().length()));
            try (var wire = new Wire(fixture.listen())) {
                var reply = wire.get("/search?q=a+b%21&tag=x&tag=%E2%82%AC");
                assertThat(reply.status()).isEqualTo(200);
                assertThat(reply.text()).isEqualTo("/search|q=a+b%21&tag=x&tag=%E2%82%AC|a b!|[x, €]");
                // Headers and body are attached after the target is parsed; the query survives both.
                wire.write("POST /echo?id=7 HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\n\r\nabc");
                assertThat(wire.read(false).text()).isEqualTo("7|3");
                assertThat(wire.get("/search").text()).isEqualTo("/search||none|[]");
            }
        }
    }

    @Test void answersMalformedQueriesWith400WithoutInvokingOrEchoing() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.app.get("/search", ctx -> { calls.incrementAndGet(); return "called"; });
            var server = fixture.listen();
            for (var query : new String[] {"q=%zz", "q=%FF", "secret=%C3%28", "q=%"}) {
                try (var wire = new Wire(server)) {
                    var reply = wire.get("/search?" + query);
                    assertThat(reply.status()).as(query).isEqualTo(400);
                    assertThat(reply.headers()).containsEntry("Content-Type", "application/problem+json");
                    assertThat(reply.text()).doesNotContain(query).doesNotContain("secret");
                }
            }
        }
        assertThat(calls).hasValue(0);
    }
}
