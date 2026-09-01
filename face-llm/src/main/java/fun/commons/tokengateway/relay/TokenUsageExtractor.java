package fun.commons.tokengateway.relay;

import java.util.Map;

/**
 * 从上游响应解析 token 用量.
 *
 * <p>覆盖两种 shape:
 * <ul>
 *   <li>OpenAI: usage.prompt_tokens / completion_tokens / prompt_tokens_details.cached_tokens</li>
 *   <li>Anthropic: usage.input_tokens / output_tokens / cache_read_input_tokens</li>
 * </ul>
 * <p>缺失字段 / null usage → 返 ZERO, 不抛异常 (调用方拿 0 透传给 settle).
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
        if (raw.get("prompt_tokens_details") instanceof Map<?, ?> details) {
            cached = toInt(details.get("cached_tokens"));
        }
        return new TokenUsage(prompt, completion, cached);
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
        return new TokenUsage(input, output, cached);
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
}
