package fun.commons.tokengateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatTokenEstimator 单测.
 * 重点验证 P0 修复: 即使 body 为空 / 字段缺失, 也必须返回 ≥1, 避免 preConsume amount=0 绕过余额检查.
 */
class ChatTokenEstimatorTest {

    @Test
    @DisplayName("body=null → 返回 1 (避免 amount=0)")
    void nullBody() {
        assertEquals(1, ChatTokenEstimator.estimatePromptTokens(null));
    }

    @Test
    @DisplayName("body 空 → 返回 1")
    void emptyBody() {
        assertEquals(1, ChatTokenEstimator.estimatePromptTokens(new LinkedHashMap<>()));
    }

    @Test
    @DisplayName("OpenAI messages 字符串 content → len/4, 最小 1")
    void openAiStringContent() {
        Map<String, Object> body = Map.of(
                "model", "gpt-4",
                "messages", List.of(
                        Map.of("role", "user", "content", "hello world")
                )
        );
        // "hello world" = 11 字符 → 11/4 = 2
        assertEquals(2, ChatTokenEstimator.estimatePromptTokens(body));
    }

    @Test
    @DisplayName("Anthropic messages block content (text 块) → 各块累加")
    void anthropicBlockContent() {
        Map<String, Object> body = Map.of(
                "model", "claude-3",
                "messages", List.of(
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", "abc"),
                                Map.of("type", "text", "text", "defghij")
                        ))
                )
        );
        // "abc" 3/4=0 → 1, "defghij" 7/4=1 → 累加 2
        assertEquals(2, ChatTokenEstimator.estimatePromptTokens(body));
    }

    @Test
    @DisplayName("Image / generic prompt 字段 → 直接计算")
    void imagePrompt() {
        Map<String, Object> body = Map.of("prompt", "a very long prompt ".repeat(10));
        assertTrue(ChatTokenEstimator.estimatePromptTokens(body) >= 10);
    }

    @Test
    @DisplayName("Embeddings input 数组 → 累加每个字符串")
    void embeddingsInputArray() {
        Map<String, Object> body = Map.of(
                "model", "text-embedding-3",
                "input", List.of("hello", "world")
        );
        // "hello" 5/4=1, "world" 5/4=1 → 累加 2
        assertEquals(2, ChatTokenEstimator.estimatePromptTokens(body));
    }

    @Test
    @DisplayName("estimateCompletionTokens: 流式 512, 非流式 256")
    void completionEstimate() {
        assertEquals(256, ChatTokenEstimator.estimateCompletionTokens(false));
        assertEquals(512, ChatTokenEstimator.estimateCompletionTokens(true));
    }
}