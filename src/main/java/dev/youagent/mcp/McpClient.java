package dev.youagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class McpClient implements AutoCloseable {
    public static final String PROTOCOL_VERSION = "2025-03-26";
    public static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(PROTOCOL_VERSION);
    private final String serverName;
    private final McpTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong ids = new AtomicLong(1);
    private volatile McpLifecycle lifecycle = McpLifecycle.NEW;
    private volatile String negotiatedProtocol;

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = normalizeName(serverName);
        this.transport = transport;
    }

    public synchronized void start(Duration timeout) throws IOException {
        if (lifecycle == McpLifecycle.READY) {
            return;
        }
        if (lifecycle == McpLifecycle.CLOSED) {
            throw new IOException("MCP client is closed");
        }
        lifecycle = McpLifecycle.STARTING;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            transport.start(remaining(deadline));
            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.set("capabilities", mapper.createObjectNode());
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "you-agent-cli");
            clientInfo.put("version", "0.2.0-SNAPSHOT");
            JsonNode response = request("initialize", params, remaining(deadline));
            JsonNode result = result(response);
            negotiatedProtocol = result.path("protocolVersion").asText();
            if (!SUPPORTED_PROTOCOL_VERSIONS.contains(negotiatedProtocol)) {
                throw new IOException("Unsupported MCP protocol version: "
                        + (negotiatedProtocol.isBlank() ? "<missing>" : negotiatedProtocol));
            }
            ObjectNode initialized = notification("notifications/initialized", mapper.createObjectNode());
            transport.notify(initialized);
            lifecycle = McpLifecycle.READY;
        } catch (IOException | RuntimeException failure) {
            lifecycle = McpLifecycle.FAILED;
            try {
                transport.close();
            } catch (IOException ignored) {
            }
            throw failure;
        }
    }

    public List<McpRemoteTool> listTools(Duration timeout) throws IOException {
        requireReady();
        JsonNode response = request("tools/list", mapper.createObjectNode(), timeout);
        List<McpRemoteTool> tools = new ArrayList<>();
        for (JsonNode node : result(response).path("tools")) {
            ObjectNode schema = node.path("inputSchema").isObject()
                    ? (ObjectNode) node.path("inputSchema").deepCopy()
                    : mapper.createObjectNode().put("type", "object");
            tools.add(new McpRemoteTool(node.path("name").asText(), node.path("description").asText(), schema));
        }
        return tools;
    }

    public JsonNode callTool(String toolName, JsonNode arguments, Duration timeout) throws IOException {
        requireReady();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return result(request("tools/call", params, timeout));
    }

    public McpLifecycle lifecycle() {
        return lifecycle;
    }

    public String serverName() {
        return serverName;
    }

    public String negotiatedProtocol() {
        return negotiatedProtocol;
    }

    @Override
    public synchronized void close() throws IOException {
        if (lifecycle == McpLifecycle.CLOSED) {
            return;
        }
        transport.close();
        lifecycle = McpLifecycle.CLOSED;
    }

    private JsonNode request(String method, JsonNode params, Duration timeout) throws IOException {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", ids.getAndIncrement());
        request.put("method", method);
        request.set("params", params);
        try {
            return transport.request(request, timeout);
        } catch (IOException failure) {
            if (lifecycle == McpLifecycle.READY && isTimeout(failure)) {
                failAndClose(failure);
            }
            throw failure;
        }
    }

    private ObjectNode notification(String method, JsonNode params) {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        return notification;
    }

    private JsonNode result(JsonNode response) throws IOException {
        if (response.has("error")) {
            throw new IOException("MCP error " + response.path("error").path("code").asInt() + ": "
                    + response.path("error").path("message").asText());
        }
        if (!response.has("result")) {
            throw new IOException("Invalid MCP response: missing result");
        }
        return response.path("result");
    }

    private void requireReady() throws IOException {
        if (lifecycle != McpLifecycle.READY) {
            throw new IOException("MCP client is not ready: " + lifecycle);
        }
    }

    private synchronized void failAndClose(IOException failure) {
        lifecycle = McpLifecycle.FAILED;
        try {
            transport.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof InterruptedIOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("timed out") || lower.contains("timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static Duration remaining(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new IOException("MCP startup timed out");
        }
        return Duration.ofNanos(nanos);
    }

    private static String normalizeName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,48}")) {
            throw new IllegalArgumentException("invalid MCP server name");
        }
        return name.toLowerCase().replace('-', '_');
    }
}
