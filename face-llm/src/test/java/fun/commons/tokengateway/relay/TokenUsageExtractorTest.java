package fun.commons.tokengateway.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenUsageExtractor 单测: 从上游响应解析 prompt/completion/cached tokens.
 *
 * <p>覆盖 OpenAI / Anthropic 两种响应 shape.
 */
@DisplayName("TokenUsageExtractor")
class TokenUsageExtractorTest {

    @Test
    @DisplayName("OpenAI shape: usage.prompt_tokens / completion_tokens / cached_tokens")
    void openAiShape() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", 36);
        usage.put("completion_tokens", 16);
        usage.put("prompt_tokens_details", Map.of("cached_tokens", 128));
        Map<String, Object> response = Map.of("usage", usage);

        TokenUsage u = TokenUsageExtractor.fromOpenAi(response);
        assertThat(u.promptTokens()).isEqualTo(36);
        assertThat(u.completionTokens()).isEqualTo(16);
        assertThat(u.cachedTokens()).isEqualTo(128);
    }

    @Test
    @DisplayName("OpenAI shape: 无 cached_tokens 字段 → 0")
    void openAiNoCached() {
        Map<String, Object> usage = Map.of("prompt_tokens", 10, "completion_tokens", 5);
        TokenUsage u = TokenUsageExtractor.fromOpenAi(Map.of("usage", usage));
        assertThat(u.cachedTokens()).isZero();
    }

    @Test
    @DisplayName("OpenAI shape: 缺 usage 字段 → 全 0 (不抛异常)")
    void openAiNoUsage() {
        TokenUsage u = TokenUsageExtractor.fromOpenAi(Map.of());
        assertThat(u.promptTokens()).isZero();
        assertThat(u.completionTokens()).isZero();
    }

    @Test
    @DisplayName("Anthropic shape: input_tokens / output_tokens / cache_read_input_tokens")
    void anthropicShape() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 36);
        usage.put("output_tokens", 16);
        usage.put("cache_read_input_tokens", 128);
        Map<String, Object> response = Map.of("usage", usage);

        TokenUsage u = TokenUsageExtractor.fromAnthropic(response);
        assertThat(u.promptTokens()).isEqualTo(36);
        assertThat(u.completionTokens()).isEqualTo(16);
        assertThat(u.cachedTokens()).isEqualTo(128);
    }

    @Test
    @DisplayName("Anthropic shape: 缺 usage 字段 → 全 0")
    void anthropicNoUsage() {
        TokenUsage u = TokenUsageExtractor.fromAnthropic(Map.of("type", "message"));
        assertThat(u.promptTokens()).isZero();
        assertThat(u.completionTokens()).isZero();
    }

    @Test
    @DisplayName("OpenAI usage 是 null Map → 全 0 (防 NPE)")
    void openAiNullUsage() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("usage", null);
        TokenUsage u = TokenUsageExtractor.fromOpenAi(response);
        assertThat(u.promptTokens()).isZero();
    }
}
