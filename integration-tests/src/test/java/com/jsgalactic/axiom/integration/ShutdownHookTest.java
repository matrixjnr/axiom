package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code closeOnJvmShutdown()} in a real JVM: SIGTERM drains the listener, so a request already
 * running finishes and is answered before the process exits.
 */
@Tag("integration")
@Timeout(120)
class ShutdownHookTest {
    @Test void sigtermLetsARunningRequestFinishBeforeTheProcessExits() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"),
                "SIGTERM through kill is not available on Windows");
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ShutdownHookMain.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            String line;
            do { line = out.readLine(); } while (line != null && !line.startsWith("LISTENING "));
            assertThat(line).as("child reported its port").isNotNull();
            int port = Integer.parseInt(line.substring("LISTENING ".length()));
            var response = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/slow")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(out.readLine()).isEqualTo("ENTERED");
            // SIGTERM while the handler is running; the hook must wait for it, not kill it.
            // (Process.destroy would also close the child's stdin, which releases the handler.)
            assertThat(new ProcessBuilder("kill", "-TERM", Long.toString(child.pid())).start().waitFor()).isZero();
            // Release the handler: the response still reaches the client during the drain.
            child.getOutputStream().write('\n');
            child.getOutputStream().flush();
            var reply = response.get(60, TimeUnit.SECONDS);
            assertThat(reply.statusCode()).isEqualTo(200);
            assertThat(reply.body()).isEqualTo("finished");
            assertThat(reply.headers().firstValue("connection")).contains("close");
            assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as("terminated by SIGTERM after the hook ran").isEqualTo(143);
        } finally {
            child.destroyForcibly();
        }
    }
}
