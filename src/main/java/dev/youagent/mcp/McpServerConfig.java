package dev.youagent.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public record McpServerConfig(
        String name,
        Transport transport,
        List<String> command,
        URI endpoint,
        Map<String, String> environment,
        Map<String, String> headers,
        Duration timeout,
        boolean enabled
) {
    public McpServerConfig {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,48}")) {
            throw new IllegalArgumentException("invalid MCP server name");
        }
        if (transport == null) {
            throw new IllegalArgumentException("MCP transport is required for " + name);
        }
        command = command == null ? List.of() : List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(15) : timeout;
        if (transport == Transport.STDIO && command.isEmpty()) {
            throw new IllegalArgumentException("stdio MCP server requires command: " + name);
        }
        if (transport == Transport.STREAMABLE_HTTP && endpoint == null) {
            throw new IllegalArgumentException("streamable-http MCP server requires url: " + name);
        }
    }

    public enum Transport {
        STDIO,
        STREAMABLE_HTTP
    }
}
