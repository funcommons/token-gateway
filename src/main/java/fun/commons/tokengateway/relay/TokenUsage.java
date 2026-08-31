package fun.commons.tokengateway.relay;

/**
 * Token 用量提取结果 (从上游响应 usage 字段解析).
 *
 * @param promptTokens OpenAI: prompt_tokens; Anthropic: input_tokens
 * @param completionTokens OpenAI: completion_tokens; Anthropic: output_tokens
 * @param cachedTokens OpenAI: prompt_tokens_details.cached_tokens;
 *                     Anthropic: cache_read_input_tokens
 */
public record TokenUsage(int promptTokens, int completionTokens, int cachedTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);
}
