package fun.commons.tokengateway.relay;

import java.util.List;
import java.util.Map;

/**
 * 内容审核辅助: 上游响应文本提取 (输出审查用).
 *
 * <p>输入侧提取/MASK 改写由 RelayOrchestrator 静态方法承担
 * ({@code extractUserContent} / {@code applyMask}).
 */
public final class ModerationSupport {

    private ModerationSupport() {
    }

    /**
     * 从 OpenAI 响应提取 completion 文本 (choices[].message.content).
     */
    public static String extractOpenAiCompletionContent(Map<String, Object> resp) {
        Object choices = resp != null ? resp.get("choices") : null;
        if (!(choices instanceof List<?> list)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object c : list) {
            if (c instanceof Map<?, ?> choice && choice.get("message") instanceof Map<?, ?> msg) {
                sb.append(contentToText(msg.get("content")));
            }
        }
        return sb.toString();
    }

    /**
     * 从 Anthropic 响应提取 completion 文本 (content[] blocks 的 text).
     */
    public static String extractAnthropicCompletionContent(Map<String, Object> resp) {
        Object content = resp != null ? resp.get("content") : null;
        return contentToText(content);
    }

    private static String contentToText(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object part : list) {
                if (part instanceof Map<?, ?> block && block.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return "";
    }
}
