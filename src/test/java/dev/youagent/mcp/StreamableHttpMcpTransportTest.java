package dev.youagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamableHttpMcpTransportTest {
    @Test
    void parsesEventStreamAndReusesSessionHeader() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setHeader("Mcp-Session-Id", "session-test")
                    .setBody("data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n"));
            server.enqueue(new MockResponse().setResponseCode(202));
            StreamableHttpMcpTransport transport = new StreamableHttpMcpTransport(
                    server.url("/mcp").uri(), Map.of("X-Test", "yes"), new OkHttpClient());
            ObjectMapper mapper = new ObjectMapper();
            transport.start(Duration.ofSeconds(1));

            var response = transport.request(mapper.createObjectNode().put("jsonrpc", "2.0")
                    .put("id", 1).put("method", "ping"), Duration.ofSeconds(1));
            transport.notify(mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "notice"));

            assertEquals(true, response.path("result").path("ok").asBoolean());
            server.takeRequest();
            assertEquals("session-test", server.takeRequest().getHeader("Mcp-Session-Id"));
        }
    }
}
