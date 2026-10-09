package dev.youagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class McpConfigLoader {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private final ObjectMapper mapper;
    private final Map<String, String> environment;

    public McpConfigLoader() {
        this(new ObjectMapper(), System.getenv());
    }

    McpConfigLoader(ObjectMapper mapper, Map<String, String> environment) {
        this.mapper = mapper;
        this.environment = Map.copyOf(environment);
    }

    public List<McpServerConfig> load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        JsonNode root = mapper.readTree(file.toFile());
        JsonNode servers = root.path("servers");
        if (!servers.isObject()) {
            throw new IOException("MCP config must contain an object field named 'servers'");
        }
        List<McpServerConfig> configs = new ArrayList<>();
        var fields = servers.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            try {
                configs.add(parse(entry.getKey(), entry.getValue()));
            } catch (IllegalArgumentException failure) {
                throw new IOException("Invalid MCP server '" + entry.getKey() + "': " + failure.getMessage(), failure);
            }
        }
        return List.copyOf(configs);
    }

    private McpServerConfig parse(String name, JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("configuration must be an object");
        }
        String rawTransport = node.path("transport").asText("").strip().toLowerCase();
        McpServerConfig.Transport transport = switch (rawTransport) {
            case "stdio" -> McpServerConfig.Transport.STDIO;
            case "streamable-http", "streamable_http", "http" -> McpServerConfig.Transport.STREAMABLE_HTTP;
            default -> throw new IllegalArgumentException("transport must be stdio or streamable-http");
        };
        boolean enabled = node.path("enabled").asBoolean(true);
        List<String> command = new ArrayList<>();
        for (JsonNode item : node.path("command")) {
            command.add(enabled ? resolve(item.asText(), name) : item.asText());
        }
        URI endpoint = null;
        if (node.hasNonNull("url") && !node.path("url").asText().isBlank()) {
            endpoint = URI.create(enabled ? resolve(node.path("url").asText(), name) : node.path("url").asText());
        }
        int timeoutSeconds = node.path("timeoutSeconds").asInt(15);
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 300");
        }
        return new McpServerConfig(name, transport, command, endpoint,
                stringMap(node.path("env"), name, enabled), stringMap(node.path("headers"), name, enabled),
                Duration.ofSeconds(timeoutSeconds), enabled);
    }

    private Map<String, String> stringMap(JsonNode node, String serverName, boolean resolvePlaceholders) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("env and headers must be objects");
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), resolvePlaceholders
                ? resolve(entry.getValue().asText(), serverName) : entry.getValue().asText()));
        return values;
    }

    private String resolve(String value, String serverName) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String variable = matcher.group(1);
            String replacement = environment.get(variable);
            if (replacement == null) {
                throw new IllegalArgumentException("environment variable " + variable
                        + " is required by server " + serverName);
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }
}
