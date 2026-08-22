package dev.youagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youagent.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ToolRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesSchemaAndBlocksPathEscape(@TempDir Path workspace) {
        ToolRegistry tools = ToolRegistry.standard(workspace, Duration.ofSeconds(1));

        ToolExecution missing = tools.execute(new ToolCall("1", "read_file", mapper.createObjectNode()));
        ToolExecution escaped = tools.execute(new ToolCall("2", "write_file", mapper.createObjectNode()
                .put("path", "../outside.txt").put("content", "no")));

        assertFalse(missing.success());
        assertEquals("INVALID_ARGUMENTS", missing.errorCode());
        assertFalse(escaped.success());
        assertEquals("POLICY_DENIED", escaped.errorCode());
    }
}
