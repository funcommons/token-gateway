package fun.commons.tokengateway.relay;

/**
 * Token 用量提取结果 (从上游响应 usage 字段解析).
 *
 * <p>总量三维 (计费口径, 与既有语义一致):
 * @param promptTokens OpenAI: prompt_tokens; Anthropic: input_tokens (含 cached 部分)
 * @param completionTokens OpenAI: completion_tokens; Anthropic: output_tokens
 * @param cachedTokens OpenAI: prompt_tokens_details.cached_tokens;
 *                     Anthropic: cache_read_input_tokens
 *
 * <p>细分留痕维 (issue #26, 不参与计价, 无源数据即 null 不造数):
 * @param reasoningTokens OpenAI: completion_tokens_details.reasoning_tokens (completion 子集);
 *                        Anthropic 无对应字段恒 null (网关转换产物可含同名词)
 * @param audioTokens OpenAI: prompt_tokens_details.audio_tokens 与
 *                    completion_tokens_details.audio_tokens 求和的留痕维 (两侧均无 → null);
 *                    Anthropic 无对应字段恒 null
 * @param cacheCreationTokens Anthropic: cache_creation_input_tokens (issue #26 定版: 不再并入
 *                            cachedTokens, 拆分只发生在细分字段); OpenAI 恒 null
 */
public record TokenUsage(int promptTokens, int completionTokens, int cachedTokens,
                         Long reasoningTokens, Long audioTokens, Long cacheCreationTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    /** 既有三维兼容构造 (细分留痕维 = null, 不造数). */
    public TokenUsage(int promptTokens, int completionTokens, int cachedTokens) {
        this(promptTokens, completionTokens, cachedTokens, null, null, null);
    }
}
