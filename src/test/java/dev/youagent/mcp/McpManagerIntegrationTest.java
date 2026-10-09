package dev.youagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.youagent.llm.ToolCall;
import dev.youagent.tool.ToolExecution;
import dev.youagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpManagerIntegrationTest {
    @Test
    void loadsConfigRegistersCallsAndClosesRemoteTool(@TempDir Path workspace) throws Exception {
        Path config = workspace.resolve(".you-agent/mcp.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                {"servers":{"demo-server":{
                  "transport":"stdio",
                  "command":["fake-server"],
                  "env":{"TOKEN":"${TEST_MCP_TOKEN}"},
                  "timeoutSeconds":2
                }}}
                """);
        McpConfigLoader loader = new McpConfigLoader(new ObjectMapper(), Map.of("TEST_MCP_TOKEN", "secret-value"));
        List<FakeTransport> created = new ArrayList<>();
        ToolRegistry registry = ToolRegistry.standard(workspace, Duration.ofSeconds(1));
        McpManager manager = new McpManager(workspace, registry, config, loader, (server, root) -> {
            assertEquals("secret-value", server.environment().get("TOKEN"));
            FakeTransport transport = new FakeTransport();
            created.add(transport);
            return transport;
        });

        manager.start();
        ToolExecution result = registry.execute(new ToolCall("call-1", "mcp__demo_server__echo",
                new ObjectMapper().createObjectNode().put("text", "hello")));

        assertTrue(result.success());
        assertEquals("hello", result.output());
        assertEquals(1, manager.statuses().get(0).toolCount());
        assertEquals(McpLifecycle.READY, manager.statuses().get(0).lifecycle());
        assertTrue(created.get(0).initialized);
        manager.close();
        assertTrue(created.get(0).closed);
        assertEquals(McpLifecycle.CLOSED, manager.statuses().get(0).lifecycle());
    }

    @Test
    void loadsStdioAndHttpFormsAndDoesNotResolveDisabledSecrets(@TempDir Path workspace) throws Exception {
        Path config = workspace.resolve("mcp.json");
        Files.writeString(config, """
                {"servers":{
                  "local":{"transport":"stdio","command":["server","${ARG}"],"env":{"K":"${TOKEN}"}},
                  "remote":{"transport":"streamable-http","url":"https://example.com/mcp","headers":{"X-Key":"${TOKEN}"}},
                  "off":{"enabled":false,"transport":"stdio","command":["server"],"env":{"K":"${MISSING}"}}
                }}
                """);

        List<McpServerConfig> configs = new McpConfigLoader(new ObjectMapper(),
                Map.of("ARG", "--stdio", "TOKEN", "value")).load(config);

        assertEquals(List.of("server", "--stdio"), configs.get(0).command());
        assertEquals("value", configs.get(1).headers().get("X-Key"));
        assertFalse(configs.get(2).enabled());
    }

    private static final class FakeTransport implements McpTransport {
        private final ObjectMapper mapper = new ObjectMapper();
        private boolean initialized;
        private boolean closed;

        @Override public void start(Duration timeout) { }

        @Override
        public JsonNode request(ObjectNode message, Duration timeout) {
            ObjectNode response = mapper.createObjectNode();
            response.set("id", message.path("id"));
            ObjectNode result = response.putObject("result");
            switch (message.path("method").asText()) {
                case "initialize" -> result.put("protocolVersion", McpClient.PROTOCOL_VERSION);
                case "tools/list" -> {
                    ObjectNode tool = result.putArray("tools").addObject();
                    tool.put("name", "echo");
                    tool.put("description", "echo text");
                    ObjectNode schema = tool.putObject("inputSchema");
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("text").put("type", "string");
                    schema.putArray("required").add("text");
                    schema.put("additionalProperties", false);
                }
                case "tools/call" -> result.putArray("content").addObject().put("type", "text")
                        .put("text", message.path("params").path("arguments").path("text").asText());
                default -> throw new AssertionError("unexpected method");
            }
            return response;
        }

        @Override public void notify(ObjectNode message) { initialized = true; }
        @Override public void close() { closed = true; }
    }
}
