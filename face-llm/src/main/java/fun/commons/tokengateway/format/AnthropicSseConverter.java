package fun.commons.tokengateway.format;

import org.springframework.http.codec.ServerSentEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI SSE chunk → Anthropic SSE 事件序列转换 (从 backend/gateway 复制).
 *
 * <p>状态机:
 * <ol>
 *   <li>首 chunk → {@code message_start}</li>
 *   <li>首个 content delta → {@code content_block_start} (text)</li>
 *   <li>后续 content delta → {@code content_block_delta} (text_delta)</li>
 *   <li>{@code delta.tool_calls} 带 id → 关当前块 + {@code content_block_start} (tool_use)
 *       (issue #18 B3); 后续 arguments 增量 → {@code content_block_delta} (input_json_delta)</li>
 *   <li>finish_reason 出现 → 关全部开块 + {@code message_delta}</li>
 *   <li>{@link #onComplete} → {@code message_stop}</li>
 * </ol>
 * <p>块序约束: Anthropic 块严格串行 (start…delta…stop), 同一时刻仅一块打开;
 * text 与 tool_use 交错时按到达顺序关旧开新. 非线程安全: 单流单实例.
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
    private boolean messageStarted = false;
    private boolean messageStopped = false;
    private int inputTokens = 0;
    private int outputTokens = 0;
    private String stopReason = null;
    /** issue #18: message_delta 延迟到 usage 帧之后发 (OpenAI usage 在 finish 帧后到达, Anthropic 协议允许靠后). */
    private boolean messageDeltaEmitted = false;
    private boolean hasUsage = false;

    /** Anthropic 块号分配器 (text 与 tool_use 共用一个序号空间). */
    private int nextBlockIndex = 0;
    /** 当前打开的 text 块号 (null = 未开). */
    private Integer openTextBlock = null;
    /** openai tool_calls index → 块状态 (id/name 记忆供交错重开). */
    private final Map<Integer, ToolBlock> toolBlocks = new HashMap<>();
    /** 当前打开的 tool 块 (null = 未开). */
    private ToolBlock openToolBlock = null;

    private record ToolBlock(String id, String name, int blockIndex) {
    }

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

        String text = extractText(openAiChunk);
        if (text != null && !text.isEmpty()) {
            if (openToolBlock != null) {
                events.add(buildContentBlockStop(openToolBlock.blockIndex()));
                openToolBlock = null;
            }
            if (openTextBlock == null) {
                openTextBlock = nextBlockIndex++;
                events.add(buildTextBlockStart(openTextBlock));
            }
            events.add(buildTextDelta(openTextBlock, text));
        }

        for (ToolCallFragment fragment : extractToolCallFragments(openAiChunk)) {
            events.addAll(onToolCallFragment(fragment));
        }

        Object finishReason = extractFinishReason(openAiChunk);
        if (finishReason != null) {
            closeOpenBlocks(events);
            stopReason = mapFinishReason(String.valueOf(finishReason));
        }

        // issue #18: 解析上游 usage 帧 (include_usage 末帧), 并入 message_delta 的 output_tokens;
        // finish 帧已到且 usage 已见 (或上游不再发 usage) 即发 message_delta
        parseUsage(openAiChunk);
        if (stopReason != null && !messageDeltaEmitted && hasUsage) {
            events.add(buildMessageDelta());
            messageDeltaEmitted = true;
        }

        return events;
    }

    /** issue #18 (B3): tool_calls 增量 → tool_use 块事件 (首片开块带 id/name, 后续参数增量). */
    private List<ServerSentEvent<String>> onToolCallFragment(ToolCallFragment fragment) {
        List<ServerSentEvent<String>> out = new ArrayList<>();
        ToolBlock block = toolBlocks.get(fragment.index());
        boolean firstFragment = fragment.id() != null || block == null;
        if (firstFragment) {
            // 关当前开块 (text 或其他 tool 块), Anthropic 块严格串行
            if (openTextBlock != null) {
                out.add(buildContentBlockStop(openTextBlock));
                openTextBlock = null;
            } else if (openToolBlock != null) {
                out.add(buildContentBlockStop(openToolBlock.blockIndex()));
            }
            String id = fragment.id() != null ? fragment.id()
                    : (block != null ? block.id() : "toolu_" + System.nanoTime());
            String name = fragment.name() != null ? fragment.name()
                    : (block != null ? block.name() : "");
            block = new ToolBlock(id, name, nextBlockIndex++);
            toolBlocks.put(fragment.index(), block);
            openToolBlock = block;
            out.add(buildToolUseBlockStart(block));
        } else if (openToolBlock != block) {
            // 交错后回到旧 tool 块: 关当前块, 按记忆的 id/name 重开 (罕见路径)
            if (openTextBlock != null) {
                out.add(buildContentBlockStop(openTextBlock));
                openTextBlock = null;
            } else if (openToolBlock != null) {
                out.add(buildContentBlockStop(openToolBlock.blockIndex()));
            }
            block = new ToolBlock(block.id(), block.name(), nextBlockIndex++);
            toolBlocks.put(fragment.index(), block);
            openToolBlock = block;
            out.add(buildToolUseBlockStart(block));
        }
        if (fragment.arguments() != null && !fragment.arguments().isEmpty()) {
            out.add(buildInputJsonDelta(block.blockIndex(), fragment.arguments()));
        }
        return out;
    }

    @Override
    public List<ServerSentEvent<String>> onComplete() {
        if (messageStopped) {
            return Collections.emptyList();
        }
        List<ServerSentEvent<String>> events = new ArrayList<>();
        closeOpenBlocks(events);
        if (!messageStarted) {
            messageId = "msg_" + System.currentTimeMillis();
            events.add(buildMessageStart());
            messageStarted = true;
        }
        if (!messageDeltaEmitted) {
            if (stopReason == null) {
                stopReason = "end_turn";
            }
            events.add(buildMessageDelta());
            messageDeltaEmitted = true;
        }
        events.add(buildMessageStop());
        messageStopped = true;
        return events;
    }

    /** 解析 OpenAI usage 帧 (stream_options.include_usage 的末帧); 见到即置位. */
    private void parseUsage(Map<String, Object> chunk) {
        if (!(chunk.get("usage") instanceof Map<?, ?> u)) {
            return;
        }
        if (u.get("prompt_tokens") instanceof Number p) {
            inputTokens = p.intValue();
        }
        if (u.get("completion_tokens") instanceof Number c) {
            outputTokens = c.intValue();
        }
        hasUsage = true;
    }

    private void closeOpenBlocks(List<ServerSentEvent<String>> events) {
        if (openTextBlock != null) {
            events.add(buildContentBlockStop(openTextBlock));
            openTextBlock = null;
        }
        if (openToolBlock != null) {
            events.add(buildContentBlockStop(openToolBlock.blockIndex()));
            openToolBlock = null;
        }
    }

    private String extractText(Map<String, Object> chunk) {
        Object choices = chunk.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        for (Object c : list) {
            if (c instanceof Map<?, ?> choice
                    && choice.get("delta") instanceof Map<?, ?> d
                    && d.get("content") instanceof String s) {
                return s;
            }
        }
        return null;
    }

    /** 提取 delta.tool_calls 增量片 (index/id/name/arguments 各字段均可缺省). */
    private List<ToolCallFragment> extractToolCallFragments(Map<String, Object> chunk) {
        Object choices = chunk.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolCallFragment> result = new ArrayList<>();
        for (Object c : list) {
            if (!(c instanceof Map<?, ?> choice)
                    || !(choice.get("delta") instanceof Map<?, ?> d)
                    || !(d.get("tool_calls") instanceof List<?> calls)) {
                continue;
            }
            for (Object o : calls) {
                if (!(o instanceof Map<?, ?> call)) {
                    continue;
                }
                int index = call.get("index") instanceof Number n ? n.intValue() : result.size();
                String id = call.get("id") instanceof String i ? i : null;
                String name = null;
                String arguments = null;
                if (call.get("function") instanceof Map<?, ?> fn) {
                    if (fn.get("name") instanceof String nm) {
                        name = nm;
                    }
                    if (fn.get("arguments") instanceof String a) {
                        arguments = a;
                    }
                }
                result.add(new ToolCallFragment(index, id, name, arguments));
            }
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

    private ServerSentEvent<String> buildTextBlockStart(int index) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", EVENT_CONTENT_BLOCK_START);
        data.put("index", index);
        data.put("content_block", block);
        return sse(EVENT_CONTENT_BLOCK_START, data);
    }

    private ServerSentEvent<String> buildToolUseBlockStart(ToolBlock block) {
        Map<String, Object> contentBlock = new LinkedHashMap<>();
        contentBlock.put("type", "tool_use");
        contentBlock.put("id", block.id());
        contentBlock.put("name", block.name());
        contentBlock.put("input", Collections.emptyMap());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", EVENT_CONTENT_BLOCK_START);
        data.put("index", block.blockIndex());
        data.put("content_block", contentBlock);
        return sse(EVENT_CONTENT_BLOCK_START, data);
    }

    private ServerSentEvent<String> buildTextDelta(int index, String text) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("type", "text_delta");
        delta.put("text", text);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", EVENT_CONTENT_BLOCK_DELTA);
        data.put("index", index);
        data.put("delta", delta);
        return sse(EVENT_CONTENT_BLOCK_DELTA, data);
    }

    private ServerSentEvent<String> buildInputJsonDelta(int index, String partialJson) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("type", "input_json_delta");
        delta.put("partial_json", partialJson);
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

    private record ToolCallFragment(int index, String id, String name, String arguments) {
    }
}
