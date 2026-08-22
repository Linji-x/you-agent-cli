package dev.youagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpClientTest {
    @Test
    void performsHandshakeDiscoversCallsAndCloses() throws Exception {
        FakeTransport transport = new FakeTransport();
        McpClient client = new McpClient("demo-server", transport);

        client.start(Duration.ofSeconds(1));
        var tools = client.listTools(Duration.ofSeconds(1));
        JsonNode result = client.callTool("echo", transport.mapper.createObjectNode().put("text", "hello"),
                Duration.ofSeconds(1));
        client.close();

        assertEquals(McpLifecycle.CLOSED, client.lifecycle());
        assertEquals(McpClient.PROTOCOL_VERSION, client.negotiatedProtocol());
        assertEquals("echo", tools.get(0).name());
        assertEquals("hello", result.path("content").path(0).path("text").asText());
        assertTrue(transport.initializedNotification);
    }

    @Test
    void rejectsUnsupportedNegotiatedProtocolAndClosesTransport() {
        FakeTransport transport = new FakeTransport();
        transport.protocolVersion = "2099-01-01";
        McpClient client = new McpClient("demo", transport);

        assertThrows(java.io.IOException.class, () -> client.start(Duration.ofSeconds(1)));
        assertEquals(McpLifecycle.FAILED, client.lifecycle());
        assertTrue(transport.closed);
    }

    private static final class FakeTransport implements McpTransport {
        private final ObjectMapper mapper = new ObjectMapper();
        private boolean started;
        private boolean initializedNotification;
        private boolean closed;
        private String protocolVersion = McpClient.PROTOCOL_VERSION;

        @Override public void start(Duration timeout) { started = true; }

        @Override
        public JsonNode request(ObjectNode message, Duration timeout) {
            ObjectNode response = mapper.createObjectNode();
            response.set("id", message.path("id"));
            ObjectNode result = response.putObject("result");
            switch (message.path("method").asText()) {
                case "initialize" -> result.put("protocolVersion", protocolVersion);
                case "tools/list" -> {
                    ObjectNode tool = result.putArray("tools").addObject();
                    tool.put("name", "echo");
                    tool.put("description", "echo text");
                    ObjectNode schema = tool.putObject("inputSchema");
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("text").put("type", "string");
                    schema.putArray("required").add("text");
                }
                case "tools/call" -> result.putArray("content").addObject()
                        .put("type", "text").put("text", message.path("params").path("arguments").path("text").asText());
                default -> throw new AssertionError("unexpected method");
            }
            return response;
        }

        @Override public void notify(ObjectNode message) {
            initializedNotification = message.path("method").asText().equals("notifications/initialized");
        }
        @Override public void close() { started = false; closed = true; }
    }
}
