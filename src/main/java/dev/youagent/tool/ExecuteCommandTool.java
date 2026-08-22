package dev.youagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ExecuteCommandTool implements Tool {
    private static final int MAX_OUTPUT_BYTES = 64_000;
    private static final List<String> DENIED_EXECUTABLES = List.of(
            "format", "mkfs", "shutdown", "reboot", "diskpart", "sudo"
    );

    @Override public String name() { return "execute_command"; }
    @Override public String description() {
        return "Execute an argument-vector command inside the workspace without a shell.";
    }
    @Override public ObjectNode inputSchema() {
        return Schema.requiredStringArray(Schema.object(), "command", "Executable followed by arguments");
    }

    @Override
    public ToolExecution execute(JsonNode arguments, ToolContext context) throws Exception {
        List<String> command = new ArrayList<>();
        arguments.path("command").forEach(item -> command.add(item.asText()));
        if (command.isEmpty() || command.get(0).isBlank()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        String executable = command.get(0).toLowerCase();
        if (DENIED_EXECUTABLES.stream().anyMatch(executable::endsWith)) {
            throw new SecurityException("executable is denied by policy");
        }
        Process process = new ProcessBuilder(command)
                .directory(context.workspace().toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (var input = process.getInputStream()) {
                input.transferTo(output);
            } catch (Exception ignored) {
            }
        }, "you-agent-command-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(context.commandTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(1_000);
            return ToolExecution.failure("TIMEOUT", "command exceeded " + context.commandTimeout().toSeconds() + "s");
        }
        reader.join(1_000);
        byte[] bytes = output.toByteArray();
        int length = Math.min(bytes.length, MAX_OUTPUT_BYTES);
        String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
        if (bytes.length > length) {
            text += "\n[truncated]";
        }
        return process.exitValue() == 0
                ? ToolExecution.success(text.isBlank() ? "exit 0" : text.stripTrailing())
                : ToolExecution.failure("NON_ZERO_EXIT", "exit " + process.exitValue() + "\n" + text.stripTrailing());
    }
}
