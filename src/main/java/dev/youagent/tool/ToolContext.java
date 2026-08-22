package dev.youagent.tool;

import java.nio.file.Path;
import java.time.Duration;

public record ToolContext(Path workspace, Duration commandTimeout) {
    public ToolContext {
        workspace = workspace.toAbsolutePath().normalize();
    }
}
