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

    // ---- issue #26: usage 细分对齐 (reasoning / audio / cacheCreation) ----

    @Test
    @DisplayName("issue #26 OpenAI 非流式细分: reasoning_tokens + 两侧 audio_tokens 求和 + cached_tokens")
    void openAiBreakdownDims() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", 36);
        usage.put("completion_tokens", 16);
        Map<String, Object> promptDetails = new LinkedHashMap<>();
        promptDetails.put("cached_tokens", 128);
        promptDetails.put("audio_tokens", 7);
        usage.put("prompt_tokens_details", promptDetails);
        Map<String, Object> completionDetails = new LinkedHashMap<>();
        completionDetails.put("reasoning_tokens", 9);
        completionDetails.put("audio_tokens", 3);
        usage.put("completion_tokens_details", completionDetails);

        TokenUsage u = TokenUsageExtractor.fromOpenAi(Map.of("usage", usage));
        assertThat(u.promptTokens()).isEqualTo(36);
        assertThat(u.completionTokens()).isEqualTo(16);
        assertThat(u.cachedTokens()).isEqualTo(128);
        assertThat(u.reasoningTokens()).isEqualTo(9);
        // 两侧 audio 求和 (留痕维口径)
        assertThat(u.audioTokens()).isEqualTo(10);
        assertThat(u.cacheCreationTokens()).isNull();
    }

    @Test
    @DisplayName("issue #26 OpenAI 非流式无细分 → null 不造 0")
    void openAiBreakdownAbsentNull() {
        Map<String, Object> usage = Map.of("prompt_tokens", 10, "completion_tokens", 5);
        TokenUsage u = TokenUsageExtractor.fromOpenAi(Map.of("usage", usage));
        assertThat(u.promptTokens()).isEqualTo(10);
        assertThat(u.reasoningTokens()).isNull();
        assertThat(u.audioTokens()).isNull();
        assertThat(u.cacheCreationTokens()).isNull();
    }

    @Test
    @DisplayName("issue #26 Anthropic 非流式口径定版: cache_creation 独立成维, 不再并入 cached")
    void anthropicCacheCreationSplit() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 36);
        usage.put("output_tokens", 16);
        usage.put("cache_read_input_tokens", 128);
        usage.put("cache_creation_input_tokens", 64);

        TokenUsage u = TokenUsageExtractor.fromAnthropic(Map.of("usage", usage));
        // 总量字段语义不变: prompt 含 cached 部分, 拆分只发生在细分字段
        assertThat(u.promptTokens()).isEqualTo(36);
        assertThat(u.completionTokens()).isEqualTo(16);
        assertThat(u.cachedTokens()).isEqualTo(128);
        assertThat(u.cacheCreationTokens()).isEqualTo(64);
        assertThat(u.reasoningTokens()).isNull();
        assertThat(u.audioTokens()).isNull();
    }

    @Test
    @DisplayName("issue #26 Anthropic 转换产物扩展键: reasoning_tokens / audio_tokens 被读取")
    void anthropicGatewayExtensionKeys() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 10);
        usage.put("output_tokens", 5);
        usage.put("cache_read_input_tokens", 4);
        usage.put("reasoning_tokens", 3);
        usage.put("audio_tokens", 2);

        TokenUsage u = TokenUsageExtractor.fromAnthropic(Map.of("usage", usage));
        assertThat(u.cachedTokens()).isEqualTo(4);
        assertThat(u.reasoningTokens()).isEqualTo(3);
        assertThat(u.audioTokens()).isEqualTo(2);
    }

    @Test
    @DisplayName("issue #26 Anthropic 缺细分 → null (原生无 reasoning/audio 时)")
    void anthropicBreakdownAbsentNull() {
        Map<String, Object> usage = Map.of("input_tokens", 10, "output_tokens", 5);
        TokenUsage u = TokenUsageExtractor.fromAnthropic(Map.of("usage", usage));
        assertThat(u.reasoningTokens()).isNull();
        assertThat(u.audioTokens()).isNull();
        assertThat(u.cacheCreationTokens()).isNull();
    }
}
