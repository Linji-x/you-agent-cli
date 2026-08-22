package dev.youagent.mcp;

import dev.youagent.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpManager implements AutoCloseable {
    @FunctionalInterface
    interface TransportFactory {
        McpTransport create(McpServerConfig config, Path workspace);
    }

    public record ServerStatus(String name, McpLifecycle lifecycle, int toolCount, String detail) {
    }

    private final Path workspace;
    private final ToolRegistry registry;
    private final Path configFile;
    private final McpConfigLoader loader;
    private final TransportFactory transportFactory;
    private final List<McpClient> clients = new ArrayList<>();
    private final Map<String, ServerStatus> statuses = new LinkedHashMap<>();
    private boolean started;

    public McpManager(Path workspace, ToolRegistry registry) {
        this(workspace, registry, workspace.toAbsolutePath().normalize().resolve(".you-agent/mcp.json"),
                new McpConfigLoader(), McpManager::createTransport);
    }

    McpManager(Path workspace, ToolRegistry registry, Path configFile, McpConfigLoader loader,
               TransportFactory transportFactory) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.registry = registry;
        this.configFile = configFile.toAbsolutePath().normalize();
        this.loader = loader;
        this.transportFactory = transportFactory;
    }

    public synchronized void start() throws IOException {
        if (started) {
            return;
        }
        started = true;
        for (McpServerConfig config : loader.load(configFile)) {
            if (!config.enabled()) {
                statuses.put(config.name(), new ServerStatus(config.name(), McpLifecycle.CLOSED, 0, "disabled"));
                continue;
            }
            McpClient client = new McpClient(config.name(), transportFactory.create(config, workspace));
            String serverName = client.serverName();
            statuses.put(serverName, new ServerStatus(serverName, McpLifecycle.STARTING, 0, "initializing"));
            try {
                client.start(config.timeout());
                List<McpRemoteTool> tools = client.listTools(config.timeout());
                for (McpRemoteTool tool : tools) {
                    registry.register(new McpToolAdapter(client, tool, config.timeout()));
                }
                clients.add(client);
                statuses.put(serverName, new ServerStatus(serverName, McpLifecycle.READY,
                        tools.size(), "protocol=" + client.negotiatedProtocol()));
            } catch (IOException | RuntimeException failure) {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
                statuses.put(serverName, new ServerStatus(serverName, McpLifecycle.FAILED, 0,
                        failure.getClass().getSimpleName()));
            }
        }
    }

    public synchronized List<ServerStatus> statuses() {
        return Collections.unmodifiableList(new ArrayList<>(statuses.values()));
    }

    public Path configFile() {
        return configFile;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException first = null;
        for (int i = clients.size() - 1; i >= 0; i--) {
            McpClient client = clients.get(i);
            try {
                client.close();
                ServerStatus previous = statuses.get(client.serverName());
                int count = previous == null ? 0 : previous.toolCount();
                statuses.put(client.serverName(), new ServerStatus(client.serverName(), McpLifecycle.CLOSED,
                        count, "closed"));
            } catch (IOException failure) {
                if (first == null) {
                    first = failure;
                }
            }
        }
        clients.clear();
        if (first != null) {
            throw first;
        }
    }

    private static McpTransport createTransport(McpServerConfig config, Path workspace) {
        return switch (config.transport()) {
            case STDIO -> new StdioMcpTransport(config.command(), workspace, config.environment());
            case STREAMABLE_HTTP -> new StreamableHttpMcpTransport(config.endpoint(), config.headers());
        };
    }
}
