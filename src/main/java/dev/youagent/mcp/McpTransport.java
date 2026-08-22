package dev.youagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;

public interface McpTransport extends AutoCloseable {
    void start(Duration timeout) throws IOException;

    JsonNode request(ObjectNode message, Duration timeout) throws IOException;

    void notify(ObjectNode message) throws IOException;

    @Override
    void close() throws IOException;
}
