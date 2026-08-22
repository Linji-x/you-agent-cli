package dev.youagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youagent.config.AppConfig;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleClientTest {
    @Test
    void streamsResponseWithoutExposingProviderSpecificSdk(@TempDir Path workspace) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}

                            data: [DONE]

                            """));
            AppConfig config = new AppConfig(server.url("/v1").uri(), "test-key", "test-model", 3,
                    Duration.ofSeconds(1), 1_000, 80,
                    workspace.resolve("memory.jsonl"), workspace.resolve("index.db"));
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(config, new ObjectMapper(), new OkHttpClient());

            LlmResponse response = client.complete(List.of(ChatMessage.user("hello")), List.of());

            assertEquals("done", response.content());
            assertEquals(LlmResponse.FinishReason.STOP, response.finishReason());
            var request = server.takeRequest();
            assertEquals("/v1/chat/completions", request.getPath());
            assertEquals("Bearer test-key", request.getHeader("Authorization"));
        }
    }
}
