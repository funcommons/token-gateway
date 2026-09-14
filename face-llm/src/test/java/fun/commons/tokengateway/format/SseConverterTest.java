package fun.commons.tokengateway.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAiSseConverter + AnthropicSseConverter 状态机测试 (从 backend/gateway 模板复制简化).
 *
 * <p>验证:
 * <ul>
 *   <li>OpenAi: anthropic message_start/content_block_delta/message_delta → OpenAI chunk 序列</li>
 *   <li>Anthropic: OpenAI delta/finish_reason → 6 种 anthropic 事件序列</li>
 *   <li>onComplete 兜底发完最后事件 (data:[DONE] / message_stop)</li>
 * </ul>
 */
@DisplayName("SSE 转换器状态机")
class SseConverterTest {

    @Test
    @DisplayName("OpenAi: anthropic 流 → OpenAI chat.completion.chunk + [DONE]")
    void openAiConverterHappyPath() {
        OpenAiSseConverter converter = new OpenAiSseConverter();

        List<ServerSentEvent<String>> roleEvents = converter.transform(Map.of(
                "type", "message_start",
                "message", Map.of("id", "msg_9", "model", "claude-3")));
        assertThat(roleEvents).hasSize(1);
        assertThat(roleEvents.get(0).data()).contains("\"object\":\"chat.completion.chunk\"");
        assertThat(roleEvents.get(0).data()).contains("\"role\":\"assistant\"");

        List<ServerSentEvent<String>> deltaEvents = converter.transform(Map.of(
                "type", "content_block_delta",
                "delta", Map.of("type", "text_delta", "text", "你好")));
        assertThat(deltaEvents).hasSize(1);
        assertThat(deltaEvents.get(0).data()).contains("\"content\":\"你好\"");

        List<ServerSentEvent<String>> finishEvents = converter.onComplete();
        assertThat(finishEvents).hasSize(2);
        assertThat(finishEvents.get(0).data()).contains("\"finish_reason\":\"stop\"");
        assertThat(finishEvents.get(1).data()).isEqualTo("[DONE]");
    }

    @Test
    @DisplayName("OpenAi: 重复 onComplete 调用幂等 (不重复发 [DONE])")
    void openAiOnCompleteIdempotent() {
        OpenAiSseConverter converter = new OpenAiSseConverter();
        converter.transform(Map.of("type", "message_start", "message", Map.of("id", "1")));

        List<ServerSentEvent<String>> first = converter.onComplete();
        List<ServerSentEvent<String>> second = converter.onComplete();

        assertThat(first).isNotEmpty();
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("Anthropic: OpenAI 流 → message_start/content_block_*/message_delta/stop 序列")
    void anthropicConverterHappyPath() {
        AnthropicSseConverter converter = new AnthropicSseConverter();

        List<ServerSentEvent<String>> first = converter.transform(Map.of(
                "id", "chatcmpl-1", "model", "gpt-4o",
                "choices", List.of(Map.of("delta", Map.of("role", "assistant")))));
        assertThat(first).hasSize(1);
        assertThat(first.get(0).event()).isEqualTo("message_start");

        List<ServerSentEvent<String>> delta = converter.transform(Map.of(
                "choices", List.of(Map.of("delta", Map.of("content", "你好")))));
        assertThat(delta).hasSize(2);
        assertThat(delta.get(0).event()).isEqualTo("content_block_start");
        assertThat(delta.get(1).event()).isEqualTo("content_block_delta");

        // issue #18 (R4): message_delta 延迟到 usage 帧之后 (OpenAI usage 在 finish 帧后到达)
        List<ServerSentEvent<String>> finish = converter.transform(Map.of(
                "choices", List.of(Map.of("delta", Map.of(), "finish_reason", "stop"))));
        assertThat(finish).hasSize(1);
        assertThat(finish.get(0).event()).isEqualTo("content_block_stop");

        List<ServerSentEvent<String>> usage = converter.transform(Map.of(
                "choices", List.of(),
                "usage", Map.of("prompt_tokens", 11, "completion_tokens", 7)));
        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).event()).isEqualTo("message_delta");
        assertThat(usage.get(0).data()).contains("\"output_tokens\":7");

        List<ServerSentEvent<String>> stop = converter.onComplete();
        assertThat(stop).hasSize(1);
        assertThat(stop.get(0).event()).isEqualTo("message_stop");
    }

    @Test
    @DisplayName("Anthropic: 直接 onComplete (无 chunk) 仍发完整序列")
    void anthropicOnCompleteFallback() {
        AnthropicSseConverter converter = new AnthropicSseConverter();
        List<ServerSentEvent<String>> events = converter.onComplete();

        assertThat(events).isNotEmpty();
        assertThat(events.stream().map(ServerSentEvent::event))
                .contains("message_start", "message_delta", "message_stop");
    }

    @Test
    @DisplayName("issue #18 (A4): anthropic tool_use 流 → OpenAI tool_calls 增量 chunk + finish=tool_calls")
    void openAiConverterToolUseStream() {
        OpenAiSseConverter converter = new OpenAiSseConverter();
        converter.transform(Map.of("type", "message_start",
                "message", Map.of("id", "msg_t", "model", "claude-x")));

        // text 块 0 → content delta; tool_use 块 1 → tool_calls index 0 (text 不占 tool 序号)
        converter.transform(Map.of("type", "content_block_delta",
                "delta", Map.of("type", "text_delta", "text", "查一下")));

        List<ServerSentEvent<String>> toolStart = converter.transform(Map.of(
                "type", "content_block_start", "index", 1,
                "content_block", Map.of("type", "tool_use", "id", "toolu_1", "name", "get_weather")));
        assertThat(toolStart).hasSize(1);
        assertThat(toolStart.get(0).data()).contains("\"tool_calls\"");
        assertThat(toolStart.get(0).data()).contains("\"id\":\"toolu_1\"");
        assertThat(toolStart.get(0).data()).contains("\"name\":\"get_weather\"");
        assertThat(toolStart.get(0).data()).contains("\"index\":0");

        List<ServerSentEvent<String>> args1 = converter.transform(Map.of(
                "type", "content_block_delta", "index", 1,
                "delta", Map.of("type", "input_json_delta", "partial_json", "{\"city\"")));
        assertThat(args1).hasSize(1);
        assertThat(toolCallFunctionArguments(args1.get(0).data())).isEqualTo("{\"city\"");

        List<ServerSentEvent<String>> args2 = converter.transform(Map.of(
                "type", "content_block_delta", "index", 1,
                "delta", Map.of("type", "input_json_delta", "partial_json", ":\"北京\"}")));
        assertThat(args2).hasSize(1);
        assertThat(toolCallFunctionArguments(args2.get(0).data())).isEqualTo(":\"北京\"}");

        converter.transform(Map.of("type", "message_delta",
                "delta", Map.of("stop_reason", "tool_use")));
        List<ServerSentEvent<String>> finish = converter.onComplete();
        assertThat(finish.get(0).data()).contains("\"finish_reason\":\"tool_calls\"");
        assertThat(finish.get(1).data()).isEqualTo("[DONE]");
    }

    @Test
    @DisplayName("issue #18 (B3): OpenAI tool_calls 流 → tool_use 块事件 + stop_reason=tool_use")
    void anthropicConverterToolCallsStream() {
        AnthropicSseConverter converter = new AnthropicSseConverter();
        converter.transform(Map.of("id", "chatcmpl-1", "model", "gpt-4o",
                "choices", List.of(Map.of("delta", Map.of("role", "assistant")))));

        // 先文本后工具: text 块开 → tool 首片到 (空 arguments 不发增量) → 关 text 开 tool_use
        converter.transform(Map.of("choices", List.of(Map.of("delta", Map.of("content", "查一下")))));
        List<ServerSentEvent<String>> toolStart = converter.transform(Map.of(
                "choices", List.of(Map.of("delta", Map.of("tool_calls", List.of(Map.of(
                        "index", 0, "id", "call_1", "type", "function",
                        "function", Map.of("name", "get_weather", "arguments", ""))))))));
        assertThat(toolStart).hasSize(2);
        assertThat(toolStart.get(0).event()).isEqualTo("content_block_stop");
        assertThat(toolStart.get(1).event()).isEqualTo("content_block_start");
        assertThat(toolStart.get(1).data())
                .contains("\"type\":\"tool_use\"").contains("\"id\":\"call_1\"").contains("\"name\":\"get_weather\"");

        // 参数增量 (无 id, 同 index) → input_json_delta
        List<ServerSentEvent<String>> args = converter.transform(Map.of(
                "choices", List.of(Map.of("delta", Map.of("tool_calls", List.of(Map.of(
                        "index", 0, "function", Map.of("arguments", "{\"city\":\"北京\"}"))))))));
        assertThat(args).hasSize(1);
        assertThat(args.get(0).event()).isEqualTo("content_block_delta");
        com.alibaba.fastjson2.JSONObject deltaEvent =
                com.alibaba.fastjson2.JSON.parseObject(args.get(0).data());
        assertThat(deltaEvent.getJSONObject("delta").getString("type")).isEqualTo("input_json_delta");
        assertThat(deltaEvent.getJSONObject("delta").getString("partial_json"))
                .isEqualTo("{\"city\":\"北京\"}");

        // finish (tool_calls) → 关 tool 块 (message_delta 延迟到 usage 帧后)
        List<ServerSentEvent<String>> finish = converter.transform(Map.of(
                "choices", List.of(Map.of("delta", Map.of(), "finish_reason", "tool_calls"))));
        assertThat(finish).hasSize(1);
        assertThat(finish.get(0).event()).isEqualTo("content_block_stop");

        List<ServerSentEvent<String>> usage = converter.transform(Map.of(
                "choices", List.of(),
                "usage", Map.of("prompt_tokens", 11, "completion_tokens", 7)));
        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).data()).contains("\"stop_reason\":\"tool_use\"");

        List<ServerSentEvent<String>> stop = converter.onComplete();
        assertThat(stop).hasSize(1);
        assertThat(stop.get(0).event()).isEqualTo("message_stop");
    }
    /** 从 OpenAI chunk data JSON 中取 tool_calls[0].function.arguments (结构化断言, 避免转义字面量). */
    private static String toolCallFunctionArguments(String data) {
        com.alibaba.fastjson2.JSONObject chunk = com.alibaba.fastjson2.JSON.parseObject(data);
        return chunk.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
                .getJSONObject("function").getString("arguments");
    }
}
