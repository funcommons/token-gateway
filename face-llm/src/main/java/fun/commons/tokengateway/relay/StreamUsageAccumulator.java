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
 *
 * <p>issue #33 内容估算法: 正常完成但末帧无 usage 的流, completion 按「已吐内容 chars/4」
 * 兜底 (与 prompt len/4 同口径). accept() 重塑为两级门控:
 * <ul>
 *   <li>帧含 {@code "usage"} → 既有真实 usage 解析, 行为与改造前逐字节一致;</li>
 *   <li>帧含内容 delta 标记 → 累加 {@link #emittedChars()} (content 与 thinking/reasoning/
 *       tool JSON 增量都算, 均为上游真实产生的 token). 喂入帧恒为上游原始形状
 *       (SsePassthroughInvoker 在协议转换前消费), 故标记按双协议形状识别:
 *       OpenAI {@code choices[].delta.content}, Anthropic {@code text_delta /
 *       thinking_delta / input_json_delta};</li>
 *   <li>其余帧零解析直接返回 (热点路径不回退); 解析失败静默跳过不计数不打 WARN (每帧都打会刷屏).</li>
 * </ul>
 * 有真实 usage 时 emittedChars 不参与任何计算 (hasUsage 路径回归红线).
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
    /** issue #33: 已吐内容字符累计 (usage 兜底估算用, chars/4 与 prompt 同口径). */
    private int emittedChars;

    @Override
    public void accept(String frame) {
        if (frame == null) {
            return;
        }
        // 两级门控: 只做 contains 短路, 不含任何标记的帧零解析直接返回
        boolean usageFrame = frame.contains("\"usage\"");
        boolean deltaFrame = !usageFrame && isContentDeltaFrame(frame);
        if (!usageFrame && !deltaFrame) {
            return;
        }
        String data = extractDataPayload(frame);
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return;
        }
        Map<String, Object> chunk;
        try {
            chunk = com.alibaba.fastjson2.JSON.parseObject(data, Map.class);
        } catch (Exception e) {
            // 解析失败静默跳过 (不计数不打 WARN, 每帧都打会刷屏)
            log.debug("[StreamUsage] JSON parse failed: {}", e.getMessage());
            return;
        }
        if (usageFrame) {
            // 真实 usage 解析: 与 #33 改造前逐字节一致
            log.debug("[StreamUsage] frame data (first 200 chars)={}", data.length() > 200 ? data.substring(0, 200) : data);
            parseOpenAiUsage(chunk);
            parseAnthropicUsage(chunk);
            log.debug("[StreamUsage] post-parse hasUsage={}, prompt={}, completion={}, cached={}, reasoning={}, audio={}, cacheCreation={}",
                    hasUsage, promptTokens, completionTokens, cachedTokens, reasoningTokens, audioTokens, cacheCreationTokens);
        }
        if (deltaFrame) {
            accumulateEmittedChars(chunk);
        }
    }

    /**
     * 是否捕获到真实 usage (false 时调用方应走估算兜底).
     */
    public boolean hasUsage() {
        return hasUsage;
    }

    /**
     * issue #33: 已吐内容字符累计 (hasUsage=true 时不参与任何计算).
     */
    public int emittedChars() {
        return emittedChars;
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

    /**
     * issue #33: 内容 delta 帧识别 (仅 contains 短路, 双协议形状 — 喂入帧为上游原始形状,
     * 协议转换发生在帧消费之后, 见 SsePassthroughInvoker#transformFrames).
     */
    private static boolean isContentDeltaFrame(String frame) {
        // OpenAI: choices[].delta.content;
        // Anthropic: content_block_delta.delta.{text_delta, thinking_delta, input_json_delta}
        return (frame.contains("\"delta\"") && frame.contains("\"content\""))
                || frame.contains("\"text_delta\"")
                || frame.contains("\"thinking_delta\"")
                || frame.contains("\"input_json_delta\"");
    }

    /**
     * issue #33: 累加已吐内容 chars — content 与 thinking/reasoning 文本都算 (均为上游
     * 真实产生的 token), tool 调用的 partial_json 增量同理; 空串/null 计 0, 异常静默跳过.
     */
    private void accumulateEmittedChars(Map<String, Object> chunk) {
        try {
            int n = 0;
            // OpenAI shape: choices[].delta.{content, reasoning_content, reasoning}
            if (chunk.get("choices") instanceof java.util.List<?> choices) {
                for (Object c : choices) {
                    if (c instanceof Map<?, ?> choice && choice.get("delta") instanceof Map<?, ?> delta) {
                        n += stringLen(delta.get("content"));
                        n += stringLen(delta.get("reasoning_content"));
                        n += stringLen(delta.get("reasoning"));
                    }
                }
            }
            // Anthropic shape: content_block_delta.delta.{text, thinking, partial_json}
            if (chunk.get("delta") instanceof Map<?, ?> delta) {
                n += stringLen(delta.get("text"));
                n += stringLen(delta.get("thinking"));
                n += stringLen(delta.get("partial_json"));
            }
            emittedChars += n;
        } catch (Exception e) {
            log.debug("[StreamUsage] emitted chars accumulate skipped: {}", e.getMessage());
        }
    }

    private static int stringLen(Object v) {
        return v instanceof String s ? s.length() : 0;
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
