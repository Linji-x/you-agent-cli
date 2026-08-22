package dev.youagent.memory;

import java.time.Instant;

public record MemoryFact(String id, String scope, String content, Instant createdAt) {
}
