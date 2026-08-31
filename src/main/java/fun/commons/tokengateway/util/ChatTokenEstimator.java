package fun.commons.tokengateway.util;

import java.util.List;
import java.util.Map;

/**
 * LLM 请求的 prompt token 估算器 (gateway-webflux 内部).
 * <p>不依赖 jtokkit (网关模块无此依赖), 走字符级兜底:
 * <ul>
 *   <li>普通文本: len / 4 (≈ OpenAI 1 token / 4 字符)</li>
 *   <li>最小值 1 (避免 amount=0 绕过余额检查, 见设计 §A 修复 P0)</li>
 * </ul>
 * 真正的精确计量在 settle 阶段通过 {@code TokenUsageExtractor} 拿到上游 usage.
 *
 * @author system
 */
public final class ChatTokenEstimator {

    private ChatTokenEstimator() {
    }

    /**
     * 估算 OpenAI/Anthropic chat 请求的 prompt token 数.
     *
     * @param body 请求体 (含 messages / prompt / input 字段)
     * @return ≥ 1 的 token 估算值
     */
    @SuppressWarnings("unchecked")
    public static int estimatePromptTokens(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return 1;
        }
        // 1. OpenAI chat: { messages: [{role, content}] }
        Object messages = body.get("messages");
        if (messages instanceof List<?> list && !list.isEmpty()) {
            int total = 0;
            for (Object m : list) {
                if (m instanceof Map<?, ?> map) {
                    Object content = map.get("content");
                    total += estimateContent(content);
                }
            }
            return Math.max(1, total);
        }
        // 2. Anthropic messages: { messages: [{role, content}] } (content 可能是 block 数组)
        //    与 OpenAI 同结构, 已覆盖

        // 3. Image / generic: { prompt: "..." }
        Object prompt = body.get("prompt");
        if (prompt instanceof String s) {
            return Math.max(1, s.length() / 4);
        }
        // 4. Embeddings: { input: "..." | [...] }
        Object input = body.get("input");
        if (input instanceof String s) {
            return Math.max(1, s.length() / 4);
        }
        if (input instanceof List<?> list) {
            int total = 0;
            for (Object item : list) {
                if (item instanceof String s) {
                    total += s.length() / 4;
                }
            }
            return Math.max(1, total);
        }
        // 兜底: 至少 1 token, 触发 balance 检查
        return 1;
    }

    @SuppressWarnings("unchecked")
    private static int estimateContent(Object content) {
        if (content == null) {
            return 0;
        }
        if (content instanceof String s) {
            return Math.max(1, s.length() / 4);
        }
        if (content instanceof List<?> blocks) {
            int total = 0;
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> block) {
                    Object type = block.get("type");
                    if ("text".equals(type) || type == null) {
                        Object text = block.get("text");
                        if (text instanceof String s) {
                            total += Math.max(1, s.length() / 4);
                        }
                    }
                    // image / tool_use 等非文本块按固定 100 tokens 估 (保守)
                    else {
                        total += 100;
                    }
                }
            }
            return Math.max(1, total);
        }
        return Math.max(1, content.toString().length() / 4);
    }

    /**
     * 估算 completion token 数 (流式/非流式都给一个保守固定值).
     * <p>实际用量在 settle 阶段由上游 usage 帧确定, 此处只是预扣的乐观估算.
     */
    public static int estimateCompletionTokens(boolean isStream) {
        return isStream ? 512 : 256;
    }
}