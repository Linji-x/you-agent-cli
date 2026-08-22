package dev.youagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class StdioMcpTransport implements McpTransport {
    private final List<String> command;
    private final Path workspace;
    private final Map<String, String> environment;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "you-agent-mcp-stdio-reader");
        thread.setDaemon(true);
        return thread;
    });
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;

    public StdioMcpTransport(List<String> command, Path workspace, Map<String, String> environment) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("MCP command is required");
        }
        this.command = List.copyOf(command);
        this.workspace = workspace.toAbsolutePath().normalize();
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    @Override
    public synchronized void start(Duration timeout) throws IOException {
        if (process != null) {
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().putAll(environment);
        process = builder.start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        if (!process.isAlive()) {
            throw new IOException("MCP process exited during startup with code " + process.exitValue());
        }
    }

    @Override
    public synchronized JsonNode request(ObjectNode message, Duration timeout) throws IOException {
        ensureStarted();
        write(message);
        String expectedId = message.path("id").asText();
        Future<JsonNode> future = readerExecutor.submit(() -> readResponse(expectedId));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            throw new IOException("MCP stdio request timed out after " + timeout.toMillis() + "ms", timeoutFailure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP stdio request interrupted", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("MCP stdio reader failed", cause);
        }
    }

    @Override
    public synchronized void notify(ObjectNode message) throws IOException {
        ensureStarted();
        write(message);
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        readerExecutor.shutdownNow();
    }

    private JsonNode readResponse(String expectedId) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message = mapper.readTree(line);
            if (message.has("id") && message.path("id").asText().equals(expectedId)) {
                return message;
            }
        }
        throw new IOException("MCP stdio server closed stdout before responding");
    }

    private void write(ObjectNode message) throws IOException {
        writer.write(mapper.writeValueAsString(message));
        writer.newLine();
        writer.flush();
    }

    private void ensureStarted() throws IOException {
        if (process == null || !process.isAlive()) {
            throw new IOException("MCP stdio transport is not running");
        }
    }
}
