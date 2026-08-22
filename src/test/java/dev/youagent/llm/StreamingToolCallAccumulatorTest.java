package dev.youagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingToolCallAccumulatorTest {
    @Test
    void mergesIncrementalNameIdAndJsonArguments() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StreamingToolCallAccumulator accumulator = new StreamingToolCallAccumulator(mapper);
        accumulator.accept(mapper.readTree("""
                {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_","function":{"name":"write_","arguments":"{\\\"path\\\":\\\"a"}}]},"finish_reason":null}]}
                """));
        accumulator.accept(mapper.readTree("""
                {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"1","function":{"name":"file","arguments":".txt\\\",\\\"content\\\":\\\"ok\\\"}"}}]},"finish_reason":"tool_calls"}]}
                """));

        LlmResponse response = accumulator.finish();

        assertEquals("call_1", response.toolCalls().get(0).id());
        assertEquals("write_file", response.toolCalls().get(0).name());
        assertEquals("a.txt", response.toolCalls().get(0).arguments().path("path").asText());
        assertEquals(LlmResponse.FinishReason.TOOL_CALLS, response.finishReason());
    }
}
