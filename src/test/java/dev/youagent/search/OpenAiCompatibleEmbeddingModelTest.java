package dev.youagent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.youagent.config.EmbeddingConfig;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleEmbeddingModelTest {
    @Test
    void callsOpenAiCompatibleEndpointWithoutLeakingConfiguration() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":[{\"embedding\":[0.25,-0.5,1.0]}]}"));
            EmbeddingConfig config = new EmbeddingConfig(server.url("/v1").uri(),
                    "test-embedding-key", "embed-test", 128);
            OpenAiCompatibleEmbeddingModel model = new OpenAiCompatibleEmbeddingModel(
                    config, new ObjectMapper(), new OkHttpClient());

            assertArrayEquals(new double[]{0.25, -0.5, 1.0}, model.embed("hello"));
            var request = server.takeRequest();
            assertEquals("/v1/embeddings", request.getPath());
            assertEquals("Bearer test-embedding-key", request.getHeader("Authorization"));
        }
    }
}
