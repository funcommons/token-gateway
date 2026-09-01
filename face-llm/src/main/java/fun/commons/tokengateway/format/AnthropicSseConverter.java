package fun.commons.tokengateway.format;

import org.springframework.http.codec.ServerSentEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI SSE chunk → Anthropic SSE 事件序列转换 (从 backend/gateway 复制).
 *
 * <p>状态机:
 * <ol>
 *   <li>首 chunk → {@code message_start}</li>
 *   <li>首个 content delta → {@code content_block_start}</li>
 *   <li>后续 content delta → {@code content_block_delta} (text_delta)</li>
 *   <li>finish_reason 出现 → {@code content_block_stop} + {@code message_delta}</li>
 *   <li>{@link #onComplete} → {@code message_stop}</li>
 * </ol>
 * <p>非线程安全: 单流单实例.
 */
public class AnthropicSseConverter implements SseTransformer {

    private static final String EVENT_MESSAGE_START = "message_start";
    private static final String EVENT_CONTENT_BLOCK_START = "content_block_start";
    private static final String EVENT_CONTENT_BLOCK_DELTA = "content_block_delta";
    private static final String EVENT_CONTENT_BLOCK_STOP = "content_block_stop";
    private static final String EVENT_MESSAGE_DELTA = "message_delta";
    private static final String EVENT_MESSAGE_STOP = "message_stop";

    private String messageId = null;
    private String model = null;
    private int contentBlockIndex = 0;
    private boolean contentBlockOpen = false;
    private boolean messageStarted = false;
    private boolean messageStopped = false;
    private int inputTokens = 0;
    private int outputTokens = 0;
    private String stopReason = null;

    @Override
    public List<ServerSentEvent<String>> transform(Map<String, Object> openAiChunk) {
        if (openAiChunk == null || openAiChunk.isEmpty()) {
            return Collections.emptyList();
        }
        List<ServerSentEvent<String>> events = new ArrayList<>();
        if (!messageStarted) {
            messageId = String.valueOf(openAiChunk.getOrDefault("id", "msg_" + System.currentTimeMillis()));
            model = String.valueOf(openAiChunk.getOrDefault("model", ""));
            events.add(buildMessageStart());
            messageStarted = true;
        }

        List<Delta> deltas = extractDeltas(openAiChunk);
        for (Delta delta : deltas) {
            String text = delta.content();
            if (text == null || text.isEmpty()) {
                continue;
            }
            if (!contentBlockOpen) {
                events.add(buildContentBlockStart(contentBlockIndex));
                contentBlockOpen = true;
            }
            events.add(buildContentBlockDelta(contentBlockIndex, text));
        }

        Object finishReason = extractFinishReason(openAiChunk);
        if (finishReason != null) {
            if (contentBlockOpen) {
                events.add(buildContentBlockStop(contentBlockIndex));
                contentBlockOpen = false;
            }
            stopReason = mapFinishReason(String.valueOf(finishReason));
            events.add(buildMessageDelta());
        }

        return events;
    }

    @Override
    public List<ServerSentEvent<String>> onComplete() {
        if (messageStopped) {
            return Collections.emptyList();
        }
        List<ServerSentEvent<String>> events = new ArrayList<>();
        if (contentBlockOpen) {
            events.add(buildContentBlockStop(contentBlockIndex));
            contentBlockOpen = false;
        }
        if (!messageStarted) {
            events.add(buildMessageStart());
            messageStarted = true;
        }
        if (stopReason == null) {
            stopReason = "end_turn";
            events.add(buildMessageDelta());
        }
        events.add(buildMessageStop());
        messageStopped = true;
        return events;
    }

    private List<Delta> extractDeltas(Map<String, Object> chunk) {
        Object choices = chunk.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<Delta> result = new ArrayList<>();
        for (Object c : list) {
            if (!(c instanceof Map<?, ?> choice)) {
                continue;
            }
            Object delta = choice.get("delta");
            String content = null;
            String role = null;
            if (delta instanceof Map<?, ?> d) {
                if (d.get("content") instanceof String s) {
                    content = s;
                }
                if (d.get("role") instanceof String r) {
                    role = r;
                }
            }
            result.add(new Delta(content, role));
        }
        return result;
    }

    private Object extractFinishReason(Map<String, Object> chunk) {
        Object choices = chunk.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        for (Object c : list) {
            if (c instanceof Map<?, ?> choice) {
                Object fr = choice.get("finish_reason");
                if (fr != null) {
                    return fr;
                }
            }
        }
        return null;
    }

    private String mapFinishReason(String reason) {
        return switch (reason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            case "content_filter" -> "refusal";
            default -> "end_turn";
        };
    }

    private ServerSentEvent<String> buildMessageStart() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.put("content", Collections.emptyList());
        message.put("stop_reason", null);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        message.put("usage", usage);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", EVENT_MESSAGE_START);
        data.put("message", message);
        return sse(EVENT_MESSAGE_START, data);
    }

    private ServerSentEvent<String> buildContentBlockStart(int index) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", EVENT_CONTENT_BLOCK_START);
        data.put("index", index);
        data.put("content_block", block);
        return sse(EVENT_CONTENT_BLOCK_START, data);
    }

    private ServerSentEvent<String> buildContentBlockDelta(int index, String text) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("type", "text_delta");
        delta.put("text", text);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", EVENT_CONTENT_BLOCK_DELTA);
        data.put("index", index);
        data.put("delta", delta);
        return sse(EVENT_CONTENT_BLOCK_DELTA, data);
    }

    private ServerSentEvent<String> buildContentBlockStop(int index) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", EVENT_CONTENT_BLOCK_STOP);
        data.put("index", index);
        return sse(EVENT_CONTENT_BLOCK_STOP, data);
    }

    private ServerSentEvent<String> buildMessageDelta() {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("stop_reason", stopReason);
        delta.put("stop_sequence", null);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("output_tokens", outputTokens);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", EVENT_MESSAGE_DELTA);
        data.put("delta", delta);
        data.put("usage", usage);
        return sse(EVENT_MESSAGE_DELTA, data);
    }

    private ServerSentEvent<String> buildMessageStop() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", EVENT_MESSAGE_STOP);
        return sse(EVENT_MESSAGE_STOP, data);
    }

    private ServerSentEvent<String> sse(String event, Map<String, Object> data) {
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(com.alibaba.fastjson2.JSON.toJSONString(data))
                .build();
    }

    private record Delta(String content, String role) {
    }
}
