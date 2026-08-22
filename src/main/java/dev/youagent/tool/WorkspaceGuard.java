package dev.youagent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspaceGuard {
    private final Path root;

    public WorkspaceGuard(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path resolve(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path candidate = Path.of(raw);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("path escapes the workspace");
        }
        Path existing = nearestExisting(resolved);
        if (existing != null && !existing.toRealPath().startsWith(root.toRealPath())) {
            throw new SecurityException("symbolic link escapes the workspace");
        }
        return resolved;
    }

    private Path nearestExisting(Path path) {
        Path cursor = path;
        while (cursor != null && !Files.exists(cursor)) {
            cursor = cursor.getParent();
        }
        return cursor;
    }
}
