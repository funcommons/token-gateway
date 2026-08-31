package fun.commons.tokengateway.relay;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * anthropic messages 的 tool_use/tool_result 配对规整.
 *
 * <p>背景: MiniMax 等严格上游对悬空 tool_call 直接 400 (2013 tool call result
 * does not follow tool call), 而 Claude Code 在工具执行被打断/出错后会留下
 * 无 tool_result 的悬空 tool_use (下一条 user 消息是纯文本).
 *
 * <p>规则:
 * <ul>
 *   <li>悬空 tool_use → 在紧随的 user 消息头部注入合成 tool_result (is_error, interrupted);
 *       没有紧随 user 消息则新建一条</li>
 *   <li>孤儿 tool_result (上一条 assistant 无对应 tool_use) → 剔除</li>
 *   <li>无需修改时原样返回 (零拷贝)</li>
 * </ul>
 */
@Slf4j
public final class AnthropicToolChainSanitizer {

    private static final String SYNTHETIC_RESULT =
            "[tool result unavailable: execution interrupted before result was recorded]";

    private AnthropicToolChainSanitizer() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitize(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> list) || list.isEmpty()) {
            return body;
        }

        List<Object> out = new ArrayList<>(list.size());
        boolean changed = false;

        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            out.add(item);
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> msg = (Map<String, Object>) item;
            if (!"assistant".equals(msg.get("role"))) {
                continue;
            }
            Set<String> toolUseIds = collectToolUseIds(msg.get("content"));
            if (toolUseIds.isEmpty()) {
                continue;
            }

            Object nextItem = i + 1 < list.size() ? list.get(i + 1) : null;
            Map<String, Object> nextUser = nextItem instanceof Map
                    && "user".equals(((Map<String, Object>) nextItem).get("role"))
                    ? (Map<String, Object>) nextItem : null;

            Set<String> foundIds = nextUser == null
                    ? Set.of()
                    : collectToolResultIds(nextUser.get("content"));
            Set<String> missing = new LinkedHashSet<>(toolUseIds);
            missing.removeAll(foundIds);
            Set<String> orphans = new LinkedHashSet<>(foundIds);
            orphans.removeAll(toolUseIds);

            if (missing.isEmpty() && orphans.isEmpty()) {
                continue;
            }
            changed = true;
            log.info("[ToolChainSanitizer] 规整: 悬空 tool_use={}, 孤儿 tool_result={}", missing, orphans);

            List<Object> mergedBlocks = new ArrayList<>();
            for (String id : missing) {
                mergedBlocks.add(syntheticToolResult(id));
            }
            if (nextUser == null) {
                Map<String, Object> synthesized = new HashMap<>();
                synthesized.put("role", "user");
                synthesized.put("content", mergedBlocks);
                out.add(synthesized);
                continue;
            }
            // 剔除孤儿 tool_result, 保留其余块, 合成结果排在最前
            for (Object b : toBlocks(nextUser.get("content"))) {
                if (b instanceof Map && "tool_result".equals(((Map<String, Object>) b).get("type"))
                        && orphans.contains(String.valueOf(((Map<String, Object>) b).get("tool_use_id")))) {
                    continue;
                }
                mergedBlocks.add(b);
            }
            Map<String, Object> newNext = new HashMap<>(nextUser);
            newNext.put("content", mergedBlocks);
            out.add(newNext);
            i++;
        }

        if (!changed) {
            return body;
        }
        Map<String, Object> newBody = new HashMap<>(body);
        newBody.put("messages", out);
        return newBody;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> collectToolUseIds(Object content) {
        Set<String> ids = new LinkedHashSet<>();
        for (Object b : toBlocks(content)) {
            if (b instanceof Map && "tool_use".equals(((Map<String, Object>) b).get("type"))) {
                Object id = ((Map<String, Object>) b).get("id");
                if (id != null) {
                    ids.add(String.valueOf(id));
                }
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> collectToolResultIds(Object content) {
        Set<String> ids = new LinkedHashSet<>();
        for (Object b : toBlocks(content)) {
            if (b instanceof Map && "tool_result".equals(((Map<String, Object>) b).get("type"))) {
                Object id = ((Map<String, Object>) b).get("tool_use_id");
                if (id != null) {
                    ids.add(String.valueOf(id));
                }
            }
        }
        return ids;
    }

    /**
     * content 统一为块列表: String content 包装为单个 text 块.
     */
    private static List<Object> toBlocks(Object content) {
        if (content instanceof List) {
            return (List<Object>) content;
        }
        if (content instanceof String s) {
            Map<String, Object> textBlock = new HashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", s);
            return List.of(textBlock);
        }
        return List.of();
    }

    private static Map<String, Object> syntheticToolResult(String toolUseId) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", SYNTHETIC_RESULT);
        block.put("is_error", true);
        return block;
    }
}
