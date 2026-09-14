package fun.commons.tokengateway.format;

import org.springframework.http.codec.ServerSentEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 上游 SSE 事件 → OpenAI chat.completion.chunk 转换 (从 backend/gateway 复制).
 *
 * <p>状态机:
 * <ol>
 *   <li>{@code message_start} → 首 chunk (delta.role=assistant, 记 id/model)</li>
 *   <li>{@code content_block_delta} (text_delta) → content chunk (delta.content=text)</li>
 *   <li>{@code content_block_start} (tool_use) → tool_calls 首 delta (id+name, issue #18 A4)</li>
 *   <li>{@code content_block_delta} (input_json_delta) → tool_calls 参数增量 delta</li>
 *   <li>{@code message_delta} → 记录 finish_reason (映射 stop_reason), 不立即发</li>
 *   <li>{@link #onComplete} → finish chunk + {@code data:[DONE]}</li>
 * </ol>
 * <p>Anthropic block index ↔ OpenAI tool_calls index 映射: 仅 tool_use 块占用
 * tool_calls 序号 (text 块不占), 按出现顺序 0..n 递增.
 * <p>非线程安全: 单流单实例.
 */
public class OpenAiSseConverter implements SseTransformer {

    private static final String EVENT_MESSAGE_START = "message_start";
    private static final String EVENT_CONTENT_BLOCK_START = "content_block_start";
    private static final String EVENT_CONTENT_BLOCK_DELTA = "content_block_delta";
    private static final String EVENT_MESSAGE_DELTA = "message_delta";
    private static final String DONE_PAYLOAD = "[DONE]";

    private String id = null;
    private String model = null;
    private final long created = Instant.now().getEpochSecond();
    private boolean started = false;
    private boolean done = false;
    private String finishReason = null;
    /** anthropic block index → openai tool_calls index (仅 tool_use 块入表). */
    private final Map<Integer, Integer> toolIndexByBlock = new HashMap<>();
    private int nextToolIndex = 0;

    @Override
    public List<ServerSentEvent<String>> transform(Map<String, Object> anthropicEvent) {
        if (anthropicEvent == null || anthropicEvent.isEmpty()) {
            return Collections.emptyList();
        }
        Object type = anthropicEvent.get("type");
        if (!(type instanceof String eventType)) {
            return Collections.emptyList();
        }
        return switch (eventType) {
            case EVENT_MESSAGE_START -> onMessageStart(anthropicEvent);
            case EVENT_CONTENT_BLOCK_START -> onContentBlockStart(anthropicEvent);
            case EVENT_CONTENT_BLOCK_DELTA -> onContentBlockDelta(anthropicEvent);
            case EVENT_MESSAGE_DELTA -> onMessageDelta(anthropicEvent);
            default -> Collections.emptyList();
        };
    }

    @Override
    public List<ServerSentEvent<String>> onComplete() {
        if (done) {
            return Collections.emptyList();
        }
        done = true;
        List<ServerSentEvent<String>> events = new ArrayList<>();
        if (!started) {
            events.add(roleChunk());
            started = true;
        }
        Map<String, Object> finishDelta = new LinkedHashMap<>();
        events.add(chunk(finishDelta, finishReason == null ? "stop" : finishReason));
        events.add(ServerSentEvent.<String>builder(DONE_PAYLOAD).build());
        return events;
    }

    private List<ServerSentEvent<String>> onMessageStart(Map<String, Object> event) {
        if (started) {
            return Collections.emptyList();
        }
        if (event.get("message") instanceof Map<?, ?> message) {
            if (message.get("id") != null) {
                id = String.valueOf(message.get("id"));
            }
            if (message.get("model") != null) {
                model = String.valueOf(message.get("model"));
            }
        }
        started = true;
        return List.of(roleChunk());
    }

    /** issue #18 (A4): tool_use 块开块 → tool_calls 首 delta (id + name + 空 arguments). */
    private List<ServerSentEvent<String>> onContentBlockStart(Map<String, Object> event) {
        Object block = event.get("content_block");
        if (!(block instanceof Map<?, ?> contentBlock)
                || !"tool_use".equals(contentBlock.get("type"))) {
            return Collections.emptyList();
        }
        int blockIndex = event.get("index") instanceof Number n ? n.intValue() : toolIndexByBlock.size();
        int toolIndex = nextToolIndex++;
        toolIndexByBlock.put(blockIndex, toolIndex);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", String.valueOf(contentBlock.get("name")));
        function.put("arguments", "");
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("index", toolIndex);
        toolCall.put("id", String.valueOf(contentBlock.get("id")));
        toolCall.put("type", "function");
        toolCall.put("function", function);
        Map<String, Object> openaiDelta = new LinkedHashMap<>();
        openaiDelta.put("tool_calls", List.of(toolCall));
        return List.of(chunk(openaiDelta, null));
    }

    private List<ServerSentEvent<String>> onContentBlockDelta(Map<String, Object> event) {
        if (!(event.get("delta") instanceof Map<?, ?> delta)) {
            return Collections.emptyList();
        }
        if ("text_delta".equals(delta.get("type"))) {
            if (!(delta.get("text") instanceof String text) || text.isEmpty()) {
                return Collections.emptyList();
            }
            Map<String, Object> openaiDelta = new LinkedHashMap<>();
            openaiDelta.put("content", text);
            return List.of(chunk(openaiDelta, null));
        }
        // issue #18 (A4): input_json_delta → tool_calls 参数增量 (按块 index 找回 tool 序号)
        if ("input_json_delta".equals(delta.get("type"))
                && delta.get("partial_json") instanceof String fragment && !fragment.isEmpty()) {
            int blockIndex = event.get("index") instanceof Number n ? n.intValue() : -1;
            Integer toolIndex = toolIndexByBlock.get(blockIndex);
            if (toolIndex == null) {
                return Collections.emptyList();
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("arguments", fragment);
            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("index", toolIndex);
            toolCall.put("function", function);
            Map<String, Object> openaiDelta = new LinkedHashMap<>();
            openaiDelta.put("tool_calls", List.of(toolCall));
            return List.of(chunk(openaiDelta, null));
        }
        return Collections.emptyList();
    }

    private List<ServerSentEvent<String>> onMessageDelta(Map<String, Object> event) {
        if (event.get("delta") instanceof Map<?, ?> delta && delta.get("stop_reason") != null) {
            finishReason = mapStopReason(String.valueOf(delta.get("stop_reason")));
        }
        return Collections.emptyList();
    }

    private String mapStopReason(String stopReason) {
        return switch (stopReason) {
            case "end_turn" -> "stop";
            case "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            default -> "stop";
        };
    }

    private ServerSentEvent<String> roleChunk() {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        return chunk(delta, null);
    }

    private ServerSentEvent<String> chunk(Map<String, Object> delta, String finishReasonValue) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReasonValue);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("object", "chat.completion.chunk");
        data.put("created", created);
        data.put("model", model);
        data.put("choices", List.of(choice));
        return ServerSentEvent.<String>builder(com.alibaba.fastjson2.JSON.toJSONString(data)).build();
    }
}
