package fun.commons.tokengateway.relay;

import java.util.Map;

/**
 * 从上游响应解析 token 用量.
 *
 * <p>覆盖两种 shape:
 * <ul>
 *   <li>OpenAI: usage.prompt_tokens / completion_tokens / prompt_tokens_details.cached_tokens;
 *       细分留痕 (issue #26): completion_tokens_details.reasoning_tokens → reasoning,
 *       prompt/completion 两侧 audio_tokens 求和 → audio (留痕维, 不参与计价)</li>
 *   <li>Anthropic: usage.input_tokens / output_tokens / cache_read_input_tokens;
 *       口径定版 (issue #26): cache_creation_input_tokens → cacheCreationTokens,
 *       不再并入 cachedTokens (拆分只发生在细分字段, 总量字段语义不变);
 *       另容忍网关 openAiToAnthropicResponse 转换产物中的 reasoning_tokens / audio_tokens
 *       扩展键 (Anthropic 原生无此字段)</li>
 * </ul>
 * <p>缺失字段 / null usage → 返 ZERO, 不抛异常 (调用方拿 0 透传给 settle);
 * 细分维无源数据 → null 不造数.
 */
public final class TokenUsageExtractor {

    private TokenUsageExtractor() {
    }

    /**
     * 从 OpenAI 响应提取 token 用量.
     */
    public static TokenUsage fromOpenAi(Map<String, Object> response) {
        if (response == null) {
            return TokenUsage.ZERO;
        }
        Object usageObj = response.get("usage");
        if (!(usageObj instanceof Map<?, ?> raw)) {
            return TokenUsage.ZERO;
        }
        int prompt = toInt(raw.get("prompt_tokens"));
        int completion = toInt(raw.get("completion_tokens"));
        int cached = 0;
        Long promptAudio = null;
        Long completionAudio = null;
        // 网关扩展键 (anthropicToOpenAIResponse 注入, issue #26): creation 独立维回收,
        // OpenAI 官方无此键, 仅网关转换产物存在
        Long cacheCreation = null;
        if (raw.get("prompt_tokens_details") instanceof Map<?, ?> details) {
            cached = toInt(details.get("cached_tokens"));
            promptAudio = toNullableLong(details.get("audio_tokens"));
            cacheCreation = toNullableLong(details.get("cache_creation_tokens"));
        }
        Long reasoning = null;
        if (raw.get("completion_tokens_details") instanceof Map<?, ?> details) {
            reasoning = toNullableLong(details.get("reasoning_tokens"));
            completionAudio = toNullableLong(details.get("audio_tokens"));
        }
        return new TokenUsage(prompt, completion, cached,
                reasoning, sumNullable(promptAudio, completionAudio), cacheCreation);
    }

    /**
     * 从 Anthropic 响应提取 token 用量.
     */
    public static TokenUsage fromAnthropic(Map<String, Object> response) {
        if (response == null) {
            return TokenUsage.ZERO;
        }
        Object usageObj = response.get("usage");
        if (!(usageObj instanceof Map<?, ?> raw)) {
            return TokenUsage.ZERO;
        }
        int input = toInt(raw.get("input_tokens"));
        int output = toInt(raw.get("output_tokens"));
        int cached = toInt(raw.get("cache_read_input_tokens"));
        // issue #26 口径定版: creation 独立成维, 不再并入 cached
        Long cacheCreation = toNullableLong(raw.get("cache_creation_input_tokens"));
        // 网关转换产物扩展键 (openAiToAnthropicResponse 注入, 仅在有值时存在)
        Long reasoning = toNullableLong(raw.get("reasoning_tokens"));
        Long audio = toNullableLong(raw.get("audio_tokens"));
        return new TokenUsage(input, output, cached, reasoning, audio, cacheCreation);
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 可选细分维解析: 缺失/坏值 → null (不造数). */
    private static Long toNullableLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 两侧求和: 任一侧有值即求和, 双侧均无 → null (audio 求和口径, issue #26). */
    private static Long sumNullable(Long a, Long b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }
}
