package dev.youagent.memory;

import dev.youagent.llm.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTest {
    @Test
    void persistsScopesSearchesAndDeletes(@TempDir Path directory) throws Exception {
        LongTermMemory memory = new LongTermMemory(directory.resolve("memory.jsonl"));
        MemoryFact global = memory.save("global", "Prefer Java 17 and deterministic tests");
        memory.save("project-a", "Use SQLite for the code index");
        memory.save("project-b", "Temporary other project fact");

        assertEquals(2, memory.listVisible("project-a").size());
        assertEquals("Use SQLite for the code index",
                memory.search("project-a", "SQLite index", 5).get(0).content());
        assertTrue(memory.delete(global.id()));
        assertFalse(memory.delete("missing"));
        assertEquals(1, memory.listVisible("project-a").size());
    }

    @Test
    void compactsOldTurnsAndLongToolOutputWhileRetainingRecentWork() {
        String large = "x".repeat(1_200);
        List<ChatMessage> history = List.of(
                ChatMessage.system("system"),
                ChatMessage.user("original goal"),
                ChatMessage.assistant("old decision", List.of()),
                ChatMessage.tool("c1", large),
                ChatMessage.user("recent task"),
                ChatMessage.assistant("recent answer", List.of())
        );
        ContextCompactor compactor = new ContextCompactor(100, 50, 1, 120,
                ContextCompactor::deterministicSummary);

        ContextCompactor.CompressionResult result = compactor.compactIfNeeded(history);

        assertTrue(result.compressed());
        assertTrue(result.tokensAfter() < result.tokensBefore());
        assertTrue(result.messages().stream().anyMatch(message -> message.content() != null
                && message.content().contains("original goal")));
        assertTrue(result.messages().stream().anyMatch(message -> "recent task".equals(message.content())));
        assertFalse(result.messages().stream().anyMatch(message -> large.equals(message.content())));
    }
}
