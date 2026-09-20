package fun.commons.tokengateway.relay;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.Consumer;

/**
 * SSE 流式 token 用量累加器 (帧消费者).
 *
 * <p>喂入原始 SSE 帧字符串, 只解析含 {@code "usage"} 的帧:
 * <ul>
 *   <li>OpenAI: 末帧 {@code usage.{prompt_tokens, completion_tokens,
 *       prompt_tokens_details.cached_tokens}} (需请求注入 stream_options.include_usage=true);
 *       细分留痕 (issue #26): prompt_tokens_details.audio_tokens +
 *       completion_tokens_details.audio_tokens 求和 → audio,
 *       completion_tokens_details.reasoning_tokens → reasoning (帧间覆盖 latest wins)</li>
 *   <li>Anthropic: {@code message_start.message.usage.input_tokens} +
 *       {@code message_delta.usage.output_tokens} (后者累计值, 覆盖取最新);
 *       口径定版 (issue #26): cache_read_input_tokens → cachedTokens (覆盖取最新),
 *       cache_creation_input_tokens → cacheCreationTokens 独立成维 (跨帧累加),
 *       不再合并进 cachedTokens</li>
 * </ul>
 * <p>线程模型: 帧按 Reactor 串行推送, 字段用普通字段即可 (细分维用 Long 以区分 "无源" 与 0).
 */
@Slf4j
public class StreamUsageAccumulator implements Consumer<String> {

    private int promptTokens;
    private int completionTokens;
    private int cachedTokens;
    /** issue #26 细分留痕: null = 上游未回报 (不造数). */
    private Long reasoningTokens;
    private Long audioTokens;
    private Long cacheCreationTokens;
    private boolean hasUsage;

    @Override
    public void accept(String frame) {
        if (frame == null || !frame.contains("\"usage\"") && !frame.contains("\"usage\":")) {
            return;
        }
        String data = extractDataPayload(frame);
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return;
        }
        log.debug("[StreamUsage] frame data (first 200 chars)={}", data.length() > 200 ? data.substring(0, 200) : data);
        Map<String, Object> chunk;
        try {
            chunk = com.alibaba.fastjson2.JSON.parseObject(data, Map.class);
        } catch (Exception e) {
            log.debug("[StreamUsage] JSON parse failed: {}", e.getMessage());
            return;
        }
        parseOpenAiUsage(chunk);
        parseAnthropicUsage(chunk);
        log.debug("[StreamUsage] post-parse hasUsage={}, prompt={}, completion={}, cached={}, reasoning={}, audio={}, cacheCreation={}",
                hasUsage, promptTokens, completionTokens, cachedTokens, reasoningTokens, audioTokens, cacheCreationTokens);
    }

    /**
     * 是否捕获到真实 usage (false 时调用方应走估算兜底).
     */
    public boolean hasUsage() {
        return hasUsage;
    }

    public TokenUsage result() {
        return new TokenUsage(promptTokens, completionTokens, cachedTokens,
                reasoningTokens, audioTokens, cacheCreationTokens);
    }

    private void parseOpenAiUsage(Map<String, Object> chunk) {
        if (!(chunk.get("usage") instanceof Map<?, ?> usage)) {
            return;
        }
        // OpenAI 末帧: prompt_tokens / completion_tokens
        if (usage.get("prompt_tokens") instanceof Number n) {
            promptTokens = n.intValue();
            hasUsage = true;
        }
        if (usage.get("completion_tokens") instanceof Number n) {
            completionTokens = n.intValue();
            hasUsage = true;
        }
        Long promptAudio = null;
        if (usage.get("prompt_tokens_details") instanceof Map<?, ?> details) {
            if (details.get("cached_tokens") instanceof Number n) {
                cachedTokens = n.intValue();
            }
            promptAudio = toNullableLong(details.get("audio_tokens"));
        }
        Long reasoning = null;
        Long completionAudio = null;
        if (usage.get("completion_tokens_details") instanceof Map<?, ?> details) {
            reasoning = toNullableLong(details.get("reasoning_tokens"));
            completionAudio = toNullableLong(details.get("audio_tokens"));
        }
        // 单帧内 prompt/completion 两侧求和; 帧间覆盖 (latest wins, 与 completion_tokens 同语义)
        Long frameAudio = sumNullable(promptAudio, completionAudio);
        if (frameAudio != null) {
            audioTokens = frameAudio;
        }
        if (reasoning != null) {
            reasoningTokens = reasoning;
        }
    }

    private void parseAnthropicUsage(Map<String, Object> chunk) {
        Object type = chunk.get("type");
        // message_start: usage 在 chunk.message.usage
        if ("message_start".equals(type) && chunk.get("message") instanceof Map<?, ?> message
                && message.get("usage") instanceof Map<?, ?> usage) {
            if (usage.get("input_tokens") instanceof Number n) {
                promptTokens = n.intValue();
                hasUsage = true;
            }
            // issue #26 口径定版: read → cached (覆盖), creation → 独立维 (累加, 保留既有 += 语义)
            if (usage.get("cache_read_input_tokens") instanceof Number n) {
                cachedTokens = n.intValue();
            }
            if (usage.get("cache_creation_input_tokens") instanceof Number n) {
                cacheCreationTokens = accumulate(cacheCreationTokens, n);
            }
        }
        // message_delta: usage 在 chunk.usage 顶层 (Anthropic standard + MiniMax 兼容)
        if ("message_delta".equals(type) && chunk.get("usage") instanceof Map<?, ?> usage) {
            // MiniMax/部分 Anthropic 兼容上游在 message_delta 累计 input/cache (cumulative),
            // latest value 覆盖. 标准 Anthropic 仅在 message_delta 发送 output_tokens.
            if (usage.get("output_tokens") instanceof Number n) {
                completionTokens = n.intValue();
                hasUsage = true;
            }
            if (usage.get("input_tokens") instanceof Number n) {
                promptTokens = n.intValue();
                hasUsage = true;
            }
            if (usage.get("cache_read_input_tokens") instanceof Number n) {
                cachedTokens = n.intValue();
                hasUsage = true;
            }
            if (usage.get("cache_creation_input_tokens") instanceof Number n) {
                cacheCreationTokens = accumulate(cacheCreationTokens, n);
                hasUsage = true;
            }
        }
        // 兜底: 部分上游把 usage 直接放在 chunk 顶层 (无 message_start/message_delta 包装)
        if (type == null && chunk.get("usage") instanceof Map<?, ?> usage) {
            if (usage.get("input_tokens") instanceof Number n) {
                promptTokens = n.intValue();
                hasUsage = true;
            }
            if (usage.get("output_tokens") instanceof Number n) {
                completionTokens = n.intValue();
                hasUsage = true;
            }
        }
    }

    /** creation 跨帧累加 (保留既有 += 语义, 含 message_start 首帧自 0 起累). */
    private static Long accumulate(Long current, Number n) {
        return (current != null ? current : 0L) + n.longValue();
    }

    private static Long toNullableLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static Long sumNullable(Long a, Long b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }

    private static String extractDataPayload(String frame) {
        StringBuilder data = new StringBuilder();
        for (String line : frame.split("\n")) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                String payload = line.substring(5);
                data.append(payload.startsWith(" ") ? payload.substring(1) : payload);
            }
        }
        return data.length() == 0 ? null : data.toString();
    }
}
