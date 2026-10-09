package dev.youagent.memory;

import dev.youagent.llm.ChatMessage;
import dev.youagent.llm.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    void estimatesAndSummarizesToolCallsArgumentsResultsAndFailures() throws Exception {
        ToolCall write = new ToolCall("c1", "write_file", new ObjectMapper().readTree("""
                {"path":"src/main/java/App.java","content":"changed implementation"}
                """));
        List<ChatMessage> messages = List.of(
                ChatMessage.assistant("", List.of(write)),
                ChatMessage.tool("c1", "wrote src/main/java/App.java"),
                ChatMessage.tool("c2", "ERROR[NON_ZERO_EXIT]: tests failed")
        );

        String summary = ContextCompactor.deterministicSummary(messages);

        assertTrue(ContextCompactor.estimateTokens(messages) > 30);
        assertTrue(summary.contains("tool-call write_file"));
        assertTrue(summary.contains("src/main/java/App.java"));
        assertTrue(summary.contains("tests failed"));
    }

    @Test
    void retrievesChineseMemoryWithExplainableCharacterNgrams(@TempDir Path directory) throws Exception {
        LongTermMemory memory = new LongTermMemory(directory.resolve("memory.jsonl"));
        memory.save("project", "数据库索引使用 SQLite 持久化，并保存在项目目录中");
        memory.save("project", "前端页面统一使用蓝色主题");

        List<MemoryFact> results = memory.search("project", "代码索引存在哪个数据库", 2);

        assertFalse(results.isEmpty());
        assertTrue(results.get(0).content().contains("SQLite"));
    }
}
