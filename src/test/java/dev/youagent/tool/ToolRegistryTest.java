package dev.youagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.youagent.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void recursivelyValidatesNestedObjectsArraysItemsAndAdditionalProperties(@TempDir Path workspace) throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode settings = schema.putObject("properties").putObject("settings");
        settings.put("type", "object");
        ObjectNode tags = settings.putObject("properties").putObject("tags");
        tags.put("type", "array");
        ObjectNode item = tags.putObject("items");
        item.put("type", "object");
        item.putObject("properties").putObject("name").put("type", "string");
        item.putArray("required").add("name");
        item.put("additionalProperties", false);
        settings.putArray("required").add("tags");
        settings.put("additionalProperties", false);
        schema.putArray("required").add("settings");
        schema.put("additionalProperties", false);
        AtomicBoolean executed = new AtomicBoolean();
        ToolRegistry tools = new ToolRegistry(new ToolContext(workspace, Duration.ofSeconds(1)));
        tools.register(new Tool() {
            @Override public String name() { return "nested_schema"; }
            @Override public String description() { return "test nested schema"; }
            @Override public ObjectNode inputSchema() { return schema; }
            @Override public ToolExecution execute(com.fasterxml.jackson.databind.JsonNode arguments,
                                                   ToolContext context) {
                executed.set(true);
                return ToolExecution.success("ok");
            }
        });

        ToolExecution wrongItem = tools.execute(new ToolCall("1", "nested_schema",
                mapper.readTree("{\"settings\":{\"tags\":[{\"name\":7}]}}")));
        ToolExecution missingNested = tools.execute(new ToolCall("2", "nested_schema",
                mapper.readTree("{\"settings\":{\"tags\":[{}]}}")));
        ToolExecution extraNested = tools.execute(new ToolCall("3", "nested_schema",
                mapper.readTree("{\"settings\":{\"tags\":[{\"name\":\"x\",\"extra\":true}]}}")));
        ToolExecution valid = tools.execute(new ToolCall("4", "nested_schema",
                mapper.readTree("{\"settings\":{\"tags\":[{\"name\":\"x\"}]}}")));

        assertEquals("INVALID_ARGUMENTS", wrongItem.errorCode());
        assertTrue(wrongItem.output().contains("$.settings.tags[0].name"));
        assertEquals("INVALID_ARGUMENTS", missingNested.errorCode());
        assertEquals("INVALID_ARGUMENTS", extraNested.errorCode());
        assertTrue(valid.success());
        assertTrue(executed.get());
    }
}
