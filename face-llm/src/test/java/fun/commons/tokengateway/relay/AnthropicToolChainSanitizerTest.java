package fun.commons.tokengateway.relay;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AnthropicToolChainSanitizer 测试: 悬空 tool_use 补合成结果 / 孤儿 tool_result 剔除 / 零拷贝.
 */
class AnthropicToolChainSanitizerTest {

    @Test
    void nullBodyAndEmptyMessagesPassthrough() {
        assertThat(AnthropicToolChainSanitizer.sanitize(null)).isNull();
        Map<String, Object> empty = Map.of("messages", List.of());
        assertThat(AnthropicToolChainSanitizer.sanitize(empty)).isSameAs(empty);
        Map<String, Object> noMsgs = Map.of("model", "m");
        assertThat(AnthropicToolChainSanitizer.sanitize(noMsgs)).isSameAs(noMsgs);
    }

    @Test
    void intactToolChainUnchanged() {
        Map<String, Object> body = Map.of("messages", List.of(
                Map.of("role", "assistant", "content", List.of(
                        Map.of("type", "tool_use", "id", "t1", "name", "f"))),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "tool_result", "tool_use_id", "t1")))));
        assertThat(AnthropicToolChainSanitizer.sanitize(body)).isSameAs(body);
    }

    @Test
    void danglingToolUseInjectsSyntheticResult() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("messages", new java.util.ArrayList<>(List.of(
                Map.of("role", "assistant", "content", List.of(
                        Map.of("type", "tool_use", "id", "t1"),
                        Map.of("type", "tool_use", "id", "t2"))),
                Map.of("role", "user", "content", "text answer"))));
        Map<String, Object> out = AnthropicToolChainSanitizer.sanitize(body);
        assertThat(out).isNotSameAs(body);
        List<?> msgs = (List<?>) out.get("messages");
        assertThat(msgs).hasSize(2);
        // 合成结果并入紧随的 user 消息头部 (不新增消息), is_error
        Map<?, ?> user = (Map<?, ?>) msgs.get(1);
        assertThat(user.get("role")).isEqualTo("user");
        List<?> blocks = (List<?>) user.get("content");
        assertThat(blocks).hasSize(3);
        Map<?, ?> first = (Map<?, ?>) blocks.get(0);
        assertThat(first.get("type")).isEqualTo("tool_result");
        assertThat(first.get("tool_use_id")).isEqualTo("t1");
        assertThat(first.get("is_error")).isEqualTo(true);
        assertThat(String.valueOf(first.get("content"))).contains("interrupted");
        assertThat(((Map<?, ?>) blocks.get(1)).get("tool_use_id")).isEqualTo("t2");
        assertThat(((Map<?, ?>) blocks.get(2)).get("type")).isEqualTo("text");
    }

    @Test
    void danglingToolUseAtTailAppendsNewUserMessage() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("messages", new java.util.ArrayList<>(List.of(
                Map.of("role", "user", "content", "go"),
                Map.of("role", "assistant", "content", List.of(
                        Map.of("type", "tool_use", "id", "t1"))))));
        // 无紧随 user 消息 → 新建一条承载合成结果
        Map<String, Object> out = AnthropicToolChainSanitizer.sanitize(body);
        List<?> msgs = (List<?>) out.get("messages");
        assertThat(msgs).hasSize(3);
        Map<?, ?> synthesized = (Map<?, ?>) msgs.get(2);
        assertThat(synthesized.get("role")).isEqualTo("user");
        List<?> blocks = (List<?>) synthesized.get("content");
        assertThat(((Map<?, ?>) blocks.get(0)).get("tool_use_id")).isEqualTo("t1");
    }

    @Test
    void bareMapContentCollectsNothing() {
        // content 为裸 Map (非 List/String) → toBlocks 为空, 不收集不改动
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("messages", new java.util.ArrayList<>(List.of(
                Map.of("role", "assistant", "content",
                        Map.of("type", "tool_use", "id", "t1")))));
        assertThat(AnthropicToolChainSanitizer.sanitize(body)).isSameAs(body);
    }

    @Test
    void orphanToolResultRemovedAndRemainingKept() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("messages", new java.util.ArrayList<>(List.of(
                Map.of("role", "assistant", "content", "plain text"),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "tool_result", "tool_use_id", "orphan"),
                        Map.of("type", "text", "text", "real answer"))))));
        Map<String, Object> out = AnthropicToolChainSanitizer.sanitize(body);
        assertThat(out).isSameAs(body);  // assistant 无 tool_use → 孤儿不在此轮处理, 原样返回
    }

    @Test
    void orphanRemovedWhenAssistantHasToolUse() {
        // assistant 有 tool_use t1, user 却只回了别的 id (孤儿) + text → 孤儿剔除, 合成 t1 排最前
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("messages", new java.util.ArrayList<>(List.of(
                Map.of("role", "assistant", "content", List.of(
                        Map.of("type", "tool_use", "id", "t1"))),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "tool_result", "tool_use_id", "orphan"),
                        Map.of("type", "text", "text", "real answer"))))));
        Map<String, Object> out = AnthropicToolChainSanitizer.sanitize(body);
        List<?> msgs = (List<?>) out.get("messages");
        assertThat(msgs).hasSize(2);
        List<?> blocks = (List<?>) ((Map<?, ?>) msgs.get(1)).get("content");
        assertThat(blocks).hasSize(2);
        assertThat(((Map<?, ?>) blocks.get(0)).get("tool_use_id")).isEqualTo("t1");
        assertThat(((Map<?, ?>) blocks.get(1)).get("text")).isEqualTo("real answer");
    }

    @Test
    void nonAssistantAndNonMapEntriesSkipped() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("messages", new java.util.ArrayList<>(List.of(
                "junk-entry",
                Map.of("role", "user", "content", "still fine"))));
        assertThat(AnthropicToolChainSanitizer.sanitize(body)).isSameAs(body);
    }
}
