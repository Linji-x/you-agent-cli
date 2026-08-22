package dev.youagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioMcpTransportTest {
    @Test
    void requestTimeoutAbortsProcessAndReaderTask(@TempDir Path workspace) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        StdioMcpTransport transport = new StdioMcpTransport(List.of(executable, "-cp", classpath,
                SilentServer.class.getName()), workspace, Map.of());
        transport.start(Duration.ofSeconds(1));
        var request = new ObjectMapper().createObjectNode().put("jsonrpc", "2.0")
                .put("id", 1).put("method", "never-respond");

        assertThrows(java.io.IOException.class,
                () -> transport.request(request, Duration.ofMillis(150)));

        assertTrue(transport.isClosed());
        assertTrue(transport.awaitReaderTermination(Duration.ofSeconds(2)));
    }

    public static final class SilentServer {
        public static void main(String[] args) throws Exception {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                input.readLine();
                Thread.sleep(60_000);
            }
        }
    }
}
