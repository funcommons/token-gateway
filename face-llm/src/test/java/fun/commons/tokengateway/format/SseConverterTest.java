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

        List<ServerSentEvent<String>> finish = converter.transform(Map.of(
                "choices", List.of(Map.of("delta", Map.of(), "finish_reason", "stop"))));
        assertThat(finish).hasSize(2);
        assertThat(finish.get(0).event()).isEqualTo("content_block_stop");
        assertThat(finish.get(1).event()).isEqualTo("message_delta");

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
}
