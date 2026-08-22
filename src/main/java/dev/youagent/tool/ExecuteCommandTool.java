package dev.youagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ExecuteCommandTool implements Tool {
    static final int MAX_OUTPUT_BYTES = 64_000;
    private static final List<String> DENIED_EXECUTABLES = List.of(
            "format", "mkfs", "shutdown", "reboot", "diskpart", "sudo"
    );

    @Override public String name() { return "execute_command"; }
    @Override public String description() {
        return "Execute an argument-vector command starting in the workspace without a shell. "
                + "This is not a container sandbox; operating-system permissions still apply.";
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
        BoundedOutput output = new BoundedOutput(MAX_OUTPUT_BYTES);
        Thread reader = new Thread(() -> drain(process, output), "you-agent-command-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(context.commandTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            terminateTree(process);
            reader.join(2_000);
            return ToolExecution.failure("TIMEOUT", "command exceeded "
                    + context.commandTimeout().toMillis() + "ms; process tree terminated");
        }
        reader.join(2_000);
        String text = output.text();
        if (output.truncated()) {
            text += (text.isBlank() ? "" : "\n") + "[truncated at " + MAX_OUTPUT_BYTES + " bytes]";
        }
        return process.exitValue() == 0
                ? ToolExecution.success(text.isBlank() ? "exit 0" : text.stripTrailing())
                : ToolExecution.failure("NON_ZERO_EXIT", "exit " + process.exitValue() + "\n" + text.stripTrailing());
    }

    private static void drain(Process process, BoundedOutput output) {
        try (var input = process.getInputStream()) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.append(buffer, read);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void terminateTree(Process process) {
        List<ProcessHandle> descendants = process.descendants()
                .sorted(Comparator.comparingInt(ExecuteCommandTool::depth).reversed())
                .toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(500, TimeUnit.MILLISECONDS);
            descendants.forEach(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static int depth(ProcessHandle handle) {
        int depth = 0;
        var parent = handle.parent();
        while (parent.isPresent() && depth < 64) {
            depth++;
            parent = parent.get().parent();
        }
        return depth;
    }

    private static final class BoundedOutput {
        private final byte[] bytes;
        private int size;
        private boolean truncated;

        private BoundedOutput(int capacity) {
            bytes = new byte[capacity];
        }

        private synchronized void append(byte[] source, int length) {
            int accepted = Math.min(length, bytes.length - size);
            if (accepted > 0) {
                System.arraycopy(source, 0, bytes, size, accepted);
                size += accepted;
            }
            if (accepted < length) {
                truncated = true;
            }
        }

        private synchronized boolean truncated() {
            return truncated;
        }

        private synchronized String text() {
            return new String(bytes, 0, size, StandardCharsets.UTF_8);
        }
    }
}
