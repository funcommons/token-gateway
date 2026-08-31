package fun.commons.tokengateway.format;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨协议消息格式转换 (设计 §6.4).
 * <p>支持 OpenAI ↔ Anthropic ↔ Gemini 双向:
 * <ul>
 *   <li>openai → anthropic: system 拆到顶层, content 数组保留, max_tokens 缺省 4096</li>
 *   <li>openai → gemini: system 拆到 systemInstruction, assistant 改名为 model</li>
 *   <li>anthropic → openai: content blocks 拼字符串, usage 字段映射</li>
 *   <li>gemini → openai: candidates[0].parts 拼字符串, usageMetadata 映射</li>
 * </ul>
 *
 * <p>纯函数: 不修改入参, 返回新 Map/List.
 *
 * <p>MVP 双协议支持新增方法 (设计 v2 §4.2):
 * <ul>
 *   <li>{@link #flattenForModeration} — Anthropic content blocks → 纯文本, 供 Moderation 扫描</li>
 *   <li>{@link #anthropicToOpenAiBody} — Anthropic 入站请求 → 内部 OpenAI Map</li>
 *   <li>{@link #toAnthropicError} — HTTP 状态码 + message → Anthropic 错误响应体</li>
 * </ul>
 */
@Component
public class FormatConverter {

    /** Anthropic 缺省 max_tokens (API 必填) */
    public static final int ANTHROPIC_DEFAULT_MAX_TOKENS = 4096;

    /**
     * OpenAI Chat 请求 → Anthropic Messages 请求.
     */
    public Map<String, Object> openaiToAnthropic(Map<String, Object> openai) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (openai == null) {
            return result;
        }
        copyIfPresent(openai, result, "model");
        copyIfPresent(openai, result, "temperature");
        copyIfPresent(openai, result, "top_p");
        copyIfPresent(openai, result, "stop_sequences");
        Object maxTokens = openai.get("max_tokens");
        result.put("max_tokens", maxTokens != null ? maxTokens : ANTHROPIC_DEFAULT_MAX_TOKENS);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> openaiMessages = (List<Map<String, Object>>) openai.get("messages");
        List<Map<String, Object>> anthMessages = new ArrayList<>();
        String systemText = null;
        if (openaiMessages != null) {
            for (Map<String, Object> msg : openaiMessages) {
                String role = String.valueOf(msg.get("role"));
                if ("system".equalsIgnoreCase(role)) {
                    Object content = msg.get("content");
                    if (content instanceof String s) {
                        systemText = s;
                    } else if (content instanceof List<?> parts) {
                        StringBuilder sb = new StringBuilder();
                        for (Object p : parts) {
                            if (p instanceof Map<?, ?> pm && pm.get("text") instanceof String ts) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(ts);
                            }
                        }
                        systemText = sb.toString();
                    }
                    continue;
                }
                anthMessages.add(openaiMessageToAnthropic(msg));
            }
        }
        if (systemText != null) {
            result.put("system", systemText);
        }
        result.put("messages", anthMessages);
        return result;
    }

    /**
     * 单条 OpenAI 消息 → Anthropic 消息.
     */
    public Map<String, Object> openaiMessageToAnthropic(Map<String, Object> msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        String role = String.valueOf(msg.get("role"));
        result.put("role", "assistant".equalsIgnoreCase(role) ? "assistant" : "user");
        Object content = msg.get("content");
        if (content instanceof String s) {
            result.put("content", s);
        } else if (content instanceof List<?> parts) {
            result.put("content", parts);
        } else {
            result.put("content", "");
        }
        return result;
    }

    /**
     * OpenAI Chat 请求 → Gemini generateContent 请求.
     */
    public Map<String, Object> openaiToGemini(Map<String, Object> openai) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (openai == null) {
            return result;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> openaiMessages = (List<Map<String, Object>>) openai.get("messages");
        List<Map<String, Object>> contents = new ArrayList<>();
        String systemText = null;
        if (openaiMessages != null) {
            for (Map<String, Object> msg : openaiMessages) {
                String role = String.valueOf(msg.get("role"));
                if ("system".equalsIgnoreCase(role)) {
                    Object content = msg.get("content");
                    if (content instanceof String s) {
                        systemText = s;
                    }
                    continue;
                }
                contents.add(openaiMessageToGemini(msg));
            }
        }
        if (systemText != null) {
            Map<String, Object> si = new LinkedHashMap<>();
            si.put("parts", List.of(Map.of("text", systemText)));
            result.put("systemInstruction", si);
        }
        result.put("contents", contents);
        return result;
    }

    private Map<String, Object> openaiMessageToGemini(Map<String, Object> msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        String role = String.valueOf(msg.get("role"));
        result.put("role", "assistant".equalsIgnoreCase(role) ? "model" : "user");
        Object content = msg.get("content");
        if (content instanceof String s) {
            result.put("parts", List.of(Map.of("text", s)));
        } else if (content instanceof List<?> parts) {
            result.put("parts", parts);
        } else {
            result.put("parts", List.of(Map.of("text", "")));
        }
        return result;
    }

    /**
     * Anthropic Messages 响应 → OpenAI Chat 响应.
     * <p>输出完整 OpenAI Chat Completions shape:
     * <ul>
     *   <li>{@code object = "chat.completion"}</li>
     *   <li>{@code id / model / created} 透传或兜底</li>
     *   <li>{@code choices[0]}: index/message{role,content}/finish_reason (从 stop_reason 反向映射)</li>
     *   <li>{@code usage}: prompt_tokens / completion_tokens / total_tokens</li>
     * </ul>
     * OpenAI 客户端 SDK 严格要求这些字段, 缺失会解析失败.
     */
    public Map<String, Object> anthropicToOpenAIResponse(Map<String, Object> anth) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (anth == null) {
            return result;
        }

        Object id = anth.get("id");
        result.put("id", id != null ? "anthropic-" + id
                : "chatcmpl-anthropic-" + System.currentTimeMillis());
        result.put("object", "chat.completion");
        result.put("created", System.currentTimeMillis() / 1000);

        Object model = anth.get("model");
        result.put("model", model != null ? model : "");

        StringBuilder contentText = new StringBuilder();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) anth.get("content");
        if (blocks != null) {
            for (Map<String, Object> block : blocks) {
                Object type = block.get("type");
                if ("text".equals(type) && block.get("text") instanceof String t) {
                    contentText.append(t);
                }
            }
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", contentText.toString());

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", mapAnthropicStopToOpenAiFinish(
                String.valueOf(anth.get("stop_reason"))));
        result.put("choices", List.of(choice));

        Object usage = anth.get("usage");
        if (usage instanceof Map<?, ?> u) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            int inVal = toInt(u.get("input_tokens"));
            int outVal = toInt(u.get("output_tokens"));
            mapped.put("prompt_tokens", inVal);
            mapped.put("completion_tokens", outVal);
            mapped.put("total_tokens", inVal + outVal);
            Object cached = u.get("cache_read_input_tokens");
            if (cached instanceof Number && ((Number) cached).intValue() > 0) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("cached_tokens", ((Number) cached).intValue());
                mapped.put("prompt_tokens_details", details);
            }
            result.put("usage", mapped);
        }
        return result;
    }

    private String mapAnthropicStopToOpenAiFinish(String stopReason) {
        if (stopReason == null || "null".equals(stopReason)) {
            return "stop";
        }
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> "stop";
        };
    }

    /**
     * Gemini generateContent 响应 → OpenAI Chat 响应.
     */
    public Map<String, Object> geminiToOpenAIResponse(Map<String, Object> gemini, String model) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (gemini == null) {
            return result;
        }
        result.put("role", "assistant");
        result.put("model", model);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) gemini.get("candidates");
        if (candidates != null && !candidates.isEmpty()) {
            Map<String, Object> first = candidates.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) first.get("content");
            if (content != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                if (parts != null) {
                    StringBuilder sb = new StringBuilder();
                    for (Map<String, Object> part : parts) {
                        if (part.get("text") instanceof String t) {
                            if (sb.length() > 0) sb.append("");
                            sb.append(t);
                        }
                    }
                    result.put("content", sb.toString());
                } else {
                    result.put("content", "");
                }
            } else {
                result.put("content", "");
            }
        } else {
            result.put("content", "");
        }

        Object usage = gemini.get("usageMetadata");
        if (usage instanceof Map<?, ?> u) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            int prompt = toInt(u.get("promptTokenCount"));
            int completion = toInt(u.get("candidatesTokenCount"));
            int total = toInt(u.get("totalTokenCount"));
            if (total == 0) total = prompt + completion;
            mapped.put("prompt_tokens", prompt);
            mapped.put("completion_tokens", completion);
            mapped.put("total_tokens", total);
            result.put("usage", mapped);
        }
        return result;
    }

    /**
     * Anthropic 入站请求 → 内部 OpenAI Map (设计 v2 §4.2).
     * <p>MessagesController 调用此方法把 Anthropic 客户端请求转成 OpenAI 兼容内部格式,
     *   交给 RelayOrchestrator + ChannelAdaptorRegistry 处理.
     * <p>转换规则:
     * <ul>
     *   <li>顶层 system 字段 → messages[0] role=system (text 块拼字符串)</li>
     *   <li>messages 数组: 每条 message.content 可能是 String 或 block 数组</li>
     *   <li>text block → 保留原 text</li>
     *   <li>image block (source.type=base64) → OpenAI image_url (data URL)</li>
     *   <li>tool_use block → assistant message 的 tool_calls 数组 (Task #179)</li>
     *   <li>tool_result block → role=tool 的 message (Task #179)</li>
     *   <li>stop_sequences → stop, max_tokens 直传</li>
     * </ul>
     * <p>cache_control 字段不在此处剥离, 由 MessagesController 显式检查并决定拒绝/透传.
     */
    public Map<String, Object> anthropicToOpenAiBody(Map<String, Object> anthropicReq) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (anthropicReq == null) {
            return result;
        }
        copyIfPresent(anthropicReq, result, "model");
        copyIfPresent(anthropicReq, result, "temperature");
        copyIfPresent(anthropicReq, result, "top_p");
        copyIfPresent(anthropicReq, result, "max_tokens");
        Object stopSeq = anthropicReq.get("stop_sequences");
        if (stopSeq != null) {
            result.put("stop", stopSeq);
        }

        List<Map<String, Object>> openaiMessages = new ArrayList<>();
        Object systemField = anthropicReq.get("system");
        if (systemField != null) {
            String systemText = flattenSystemToText(systemField);
            if (systemText != null && !systemText.isBlank()) {
                Map<String, Object> sysMsg = new LinkedHashMap<>();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemText);
                openaiMessages.add(sysMsg);
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anthMessages = (List<Map<String, Object>>) anthropicReq.get("messages");
        if (anthMessages != null) {
            for (Map<String, Object> msg : anthMessages) {
                openaiMessages.addAll(anthropicMessageToOpenAi(msg));
            }
        }
        result.put("messages", openaiMessages);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) anthropicReq.get("tools");
        if (tools != null) {
            result.put("tools", convertAnthropicToolsToOpenAi(tools));
        }
        Object toolChoice = anthropicReq.get("tool_choice");
        if (toolChoice != null) {
            result.put("tool_choice", convertAnthropicToolChoiceToOpenAi(toolChoice));
        }
        return result;
    }

    /**
     * Anthropic tools 数组 → OpenAI tools 数组 (设计 v2 §4.2).
     * <p>Anthropic: {name, description, input_schema}
     * <p>OpenAI: {type:"function", function:{name, description, parameters}}
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertAnthropicToolsToOpenAi(List<Map<String, Object>> anthropicTools) {
        List<Map<String, Object>> result = new ArrayList<>(anthropicTools.size());
        for (Map<String, Object> tool : anthropicTools) {
            Map<String, Object> function = new LinkedHashMap<>();
            copyIfPresent(tool, function, "name");
            copyIfPresent(tool, function, "description");
            Object inputSchema = tool.get("input_schema");
            if (inputSchema != null) {
                function.put("parameters", inputSchema);
            }
            Map<String, Object> openaiTool = new LinkedHashMap<>();
            openaiTool.put("type", "function");
            openaiTool.put("function", function);
            result.add(openaiTool);
        }
        return result;
    }

    /**
     * Anthropic tool_choice → OpenAI tool_choice.
     * <p>Anthropic: "auto" | "any" | {type:"tool", name:"xxx"} | {type:"auto"|"any"}
     * <p>OpenAI: "auto" | "required" | {type:"function", function:{name:"xxx"}}
     */
    @SuppressWarnings("unchecked")
    private Object convertAnthropicToolChoiceToOpenAi(Object toolChoice) {
        if (toolChoice instanceof String s) {
            return switch (s) {
                case "auto" -> "auto";
                case "any" -> "required";
                default -> s;
            };
        }
        if (toolChoice instanceof Map<?, ?> tc) {
            Object type = tc.get("type");
            if (type instanceof String t) {
                return switch (t) {
                    case "auto" -> "auto";
                    case "any" -> "required";
                    case "tool" -> {
                        Map<String, Object> func = new LinkedHashMap<>();
                        func.put("name", tc.get("name"));
                        Map<String, Object> choice = new LinkedHashMap<>();
                        choice.put("type", "function");
                        choice.put("function", func);
                        yield choice;
                    }
                    default -> toolChoice;
                };
            }
        }
        return toolChoice;
    }

    /**
     * 单条 Anthropic 消息 → OpenAI 消息 (可能 1 → 多, 因 tool_result 块要拆为独立 role=tool 消息).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> anthropicMessageToOpenAi(Map<String, Object> msg) {
        List<Map<String, Object>> result = new ArrayList<>();
        String role = String.valueOf(msg.get("role"));
        Object content = msg.get("content");

        if (content instanceof List<?> blocks) {
            List<Map<String, Object>> openaiContent = new ArrayList<>();
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            StringBuilder textSb = new StringBuilder();
            boolean hasNonTextBlock = false;
            for (Object b : blocks) {
                if (!(b instanceof Map<?, ?> block)) {
                    continue;
                }
                Object type = block.get("type");
                if ("text".equals(type) && block.get("text") instanceof String t) {
                    if (textSb.length() > 0) {
                        textSb.append('\n');
                    }
                    textSb.append(t);
                    Map<String, Object> textPart = new LinkedHashMap<>();
                    textPart.put("type", "text");
                    textPart.put("text", t);
                    openaiContent.add(textPart);
                } else if ("image".equals(type)) {
                    Object source = block.get("source");
                    if (source instanceof Map<?, ?> src) {
                        String dataUrl = anthropicImageSourceToDataUrl(src);
                        if (dataUrl != null) {
                            Map<String, Object> imgPart = new LinkedHashMap<>();
                            imgPart.put("type", "image_url");
                            imgPart.put("image_url", Map.of("url", dataUrl));
                            openaiContent.add(imgPart);
                            hasNonTextBlock = true;
                        }
                    }
                } else if ("tool_use".equals(type)) {
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    Object id = block.get("id");
                    toolCall.put("id", id != null ? id : "call_" + System.nanoTime());
                    toolCall.put("type", "function");
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", block.get("name"));
                    Object input = block.get("input");
                    try {
                        toolCall.put("function",
                                Map.of("name", block.get("name"),
                                        "arguments",
                                        input == null ? "{}" : com.alibaba.fastjson2.JSON.toJSONString(input)));
                    } catch (Exception e) {
                        toolCall.put("function",
                                Map.of("name", block.get("name"), "arguments", "{}"));
                    }
                    toolCalls.add(toolCall);
                    hasNonTextBlock = true;
                } else if ("tool_result".equals(type)) {
                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", block.get("tool_use_id"));
                    Object nested = block.get("content");
                    toolMsg.put("content", flattenToolResultContent(nested));
                    result.add(toolMsg);
                }
            }

            if (!"assistant".equals(role) && !toolCalls.isEmpty()) {
                // tool_use 块只在 assistant 消息上有效; 其他角色上的 tool_use 忽略 (不应出现)
            }
            if ("assistant".equals(role) && !toolCalls.isEmpty()) {
                Map<String, Object> asstMsg = new LinkedHashMap<>();
                asstMsg.put("role", "assistant");
                asstMsg.put("content", textSb.length() > 0 ? textSb.toString() : null);
                asstMsg.put("tool_calls", toolCalls);
                result.add(asstMsg);
            } else if (!result.isEmpty() && (textSb.length() > 0 || hasNonTextBlock)) {
                // tool_result 已拆为独立消息, 若还有 text/image 块则补一条 user 消息
                Map<String, Object> userMsg = new LinkedHashMap<>();
                userMsg.put("role", role);
                if (hasNonTextBlock) {
                    userMsg.put("content", openaiContent);
                } else {
                    userMsg.put("content", textSb.toString());
                }
                result.add(userMsg);
            } else if (result.isEmpty()) {
                Map<String, Object> single = new LinkedHashMap<>();
                single.put("role", role);
                if (hasNonTextBlock) {
                    single.put("content", openaiContent);
                } else if (textSb.length() > 0) {
                    single.put("content", textSb.toString());
                } else {
                    single.put("content", "");
                }
                result.add(single);
            }
        } else if (content instanceof String s) {
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("role", role);
            single.put("content", s);
            result.add(single);
        } else {
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("role", role);
            single.put("content", "");
            result.add(single);
        }
        return result;
    }

    private String flattenToolResultContent(Object nested) {
        if (nested == null) {
            return "";
        }
        if (nested instanceof String s) {
            return s;
        }
        if (nested instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> block
                        && "text".equals(block.get("type"))
                        && block.get("text") instanceof String t) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return String.valueOf(nested);
    }

    @SuppressWarnings("unchecked")
    private String anthropicImageSourceToDataUrl(Map<?, ?> source) {
        Object type = source.get("type");
        if (!"base64".equals(type)) {
            return null;
        }
        Object mediaType = source.get("media_type");
        Object data = source.get("data");
        if (mediaType instanceof String mt && data instanceof String d) {
            return "data:" + mt + ";base64," + d;
        }
        return null;
    }

    private String flattenSystemToText(Object systemField) {
        if (systemField instanceof String s) {
            return s;
        }
        if (systemField instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> block && block.get("text") instanceof String t) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * OpenAI 上游响应 → Anthropic 响应 (设计 v2 §4.2).
     * <p>MessagesController 在上游协议是 OpenAI 时调用此方法把内部 OpenAI 响应
     *   转成 Anthropic 客户端期望的响应格式.
     * <p>转换规则:
     * <ul>
     *   <li>顶层 id/object → Anthropic message id + type:"message"</li>
     *   <li>choices[0].message.content → content 数组 (单 text block)</li>
     *   <li>choices[0].finish_reason → stop_reason 映射</li>
     *   <li>usage.prompt_tokens → input_tokens, completion_tokens → output_tokens</li>
     * </ul>
     * <p>tool_calls 响应转换在 Task #179 补.
     */
    public Map<String, Object> openAiToAnthropicResponse(Map<String, Object> openaiResp) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (openaiResp == null) {
            return result;
        }
        Object id = openaiResp.get("id");
        result.put("id", id != null ? id : "msg_" + System.currentTimeMillis());
        result.put("type", "message");
        result.put("role", "assistant");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) openaiResp.get("choices");
        String finishReason = null;
        String contentText = "";
        List<Map<String, Object>> toolCalls = null;
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> first = choices.get(0);
            Object message = first.get("message");
            if (message instanceof Map<?, ?> m) {
                if (m.get("content") instanceof String c) {
                    contentText = c;
                }
                Object tc = m.get("tool_calls");
                if (tc instanceof List<?> list && !list.isEmpty()) {
                    toolCalls = new ArrayList<>();
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> call) {
                            toolCalls.add((Map<String, Object>) call);
                        }
                    }
                }
            }
            Object fr = first.get("finish_reason");
            if (fr instanceof String s) {
                finishReason = s;
            }
        }

        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        if (contentText != null && !contentText.isEmpty()) {
            Map<String, Object> textBlock = new LinkedHashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", contentText);
            contentBlocks.add(textBlock);
        }
        if (toolCalls != null) {
            for (Map<String, Object> call : toolCalls) {
                Map<String, Object> toolUseBlock = new LinkedHashMap<>();
                toolUseBlock.put("type", "tool_use");
                Object callId = call.get("id");
                toolUseBlock.put("id", callId != null ? callId : "toolu_" + System.nanoTime());
                Object function = call.get("function");
                String name = null;
                String arguments = null;
                if (function instanceof Map<?, ?> func) {
                    if (func.get("name") instanceof String n) {
                        name = n;
                    }
                    if (func.get("arguments") instanceof String a) {
                        arguments = a;
                    }
                }
                toolUseBlock.put("name", name);
                Object inputParsed = parseToolInput(arguments);
                toolUseBlock.put("input", inputParsed);
                contentBlocks.add(toolUseBlock);
            }
        }
        if (contentBlocks.isEmpty()) {
            Map<String, Object> emptyText = new LinkedHashMap<>();
            emptyText.put("type", "text");
            emptyText.put("text", "");
            contentBlocks.add(emptyText);
        }
        result.put("content", contentBlocks);
        result.put("stop_reason", mapOpenAiFinishToAnthropicStop(finishReason));

        Object model = openaiResp.get("model");
        if (model != null) {
            result.put("model", model);
        }
        Object usage = openaiResp.get("usage");
        if (usage instanceof Map<?, ?> u) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("input_tokens", toInt(u.get("prompt_tokens")));
            mapped.put("output_tokens", toInt(u.get("completion_tokens")));
            result.put("usage", mapped);
        }
        return result;
    }

    private String mapOpenAiFinishToAnthropicStop(String finishReason) {
        if (finishReason == null) {
            return null;
        }
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            case "content_filter" -> "refusal";
            default -> "end_turn";
        };
    }

    /**
     * Moderation flatten (设计 v2 §4.3(7)).
     * <p>Anthropic 客户端请求的 content 可能是数组 (text/image/tool_use/tool_result 块),
     *   现有 AbstractRelayController.extractPromptContent 只认 String, 导致 Moderation 扫不到.
     * <p>此方法遍历 messages + system 字段, 把所有可扫文本拼成一个 String.
     * <ul>
     *   <li>type=text → 直接 append text</li>
     *   <li>type=image → append 占位 "[image:media_type=xxx]" (不扫二进制)</li>
     *   <li>type=tool_use → append JSON.stringify(input)</li>
     *   <li>type=tool_result → 递归 flatten content (可能是 String 或数组)</li>
     * </ul>
     */
    public String flattenForModeration(Map<String, Object> anthropicBody) {
        if (anthropicBody == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Object systemField = anthropicBody.get("system");
        if (systemField != null) {
            appendFlattened(sb, systemField);
        }
        Object messages = anthropicBody.get("messages");
        if (messages instanceof List<?> list) {
            for (Object m : list) {
                if (m instanceof Map<?, ?> msg) {
                    appendFlattened(sb, msg.get("content"));
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendFlattened(StringBuilder sb, Object content) {
        if (content == null) {
            return;
        }
        if (content instanceof String s) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
            return;
        }
        if (content instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (!(b instanceof Map<?, ?> block)) {
                    continue;
                }
                Object type = block.get("type");
                if ("text".equals(type) && block.get("text") instanceof String t) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                } else if ("image".equals(type)) {
                    Object source = block.get("source");
                    String mediaType = "unknown";
                    if (source instanceof Map<?, ?> src && src.get("media_type") instanceof String mt) {
                        mediaType = mt;
                    }
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append("[image:media_type=").append(mediaType).append("]");
                } else if ("tool_use".equals(type)) {
                    Object input = block.get("input");
                    if (input != null) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append("[tool_use:").append(input.toString()).append("]");
                    }
                } else if ("tool_result".equals(type)) {
                    Object nested = block.get("content");
                    appendFlattened(sb, nested);
                }
            }
        }
    }

    /**
     * 检测 Anthropic 请求中是否出现 cache_control 字段 (设计 v2 §4.3(6)).
     * <p>cache_control 可出现在:
     * <ul>
     *   <li>顶层 system 字段 (String 或 block 数组, block 上有 cache_control)</li>
     *   <li>messages[*].content 数组中任意 block 的 cache_control 字段</li>
     * </ul>
     * <p>MessagesController 在 OpenAI 上游场景下若检测到 cache_control 必须返 400.
     */
    public boolean hasCacheControl(Map<String, Object> anthropicBody) {
        if (anthropicBody == null) {
            return false;
        }
        if (hasCacheControlInContent(anthropicBody.get("system"))) {
            return true;
        }
        Object messages = anthropicBody.get("messages");
        if (messages instanceof List<?> list) {
            for (Object m : list) {
                if (m instanceof Map<?, ?> msg
                        && hasCacheControlInContent(msg.get("content"))) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean hasCacheControlInContent(Object content) {
        if (content instanceof Map<?, ?> block) {
            return block.get("cache_control") != null;
        }
        if (content instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> block
                        && block.get("cache_control") != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Anthropic 错误响应体 (设计 v2 §4.3(8)).
     * <p>MessagesController / RelayExceptionHandler 在返错给 Anthropic 客户端时调用.
     * <p>响应结构: {"type":"error", "error":{"type":"...", "message":"..."}}
     */
    public Map<String, Object> toAnthropicError(int statusCode, String message, String type) {
        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("type", type != null ? type : anthropicErrorType(statusCode));
        errorBody.put("message", message != null ? message : "");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "error");
        result.put("error", errorBody);
        return result;
    }

    private String anthropicErrorType(int statusCode) {
        return switch (statusCode) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 413 -> "request_too_large";
            case 429 -> "rate_limit_error";
            case 529 -> "overloaded_error";
            default -> statusCode >= 500 ? "api_error" : "invalid_request_error";
        };
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        Object v = src.get(key);
        if (v != null) dst.put(key, v);
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 解析 OpenAI tool_call.function.arguments JSON 字符串 → Anthropic tool_use.input 对象.
     * <p>解析失败兜底返回空 Map (避免上游 null/坏 JSON 导致整条响应崩).
     */
    @SuppressWarnings("unchecked")
    private static Object parseToolInput(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = com.alibaba.fastjson2.JSON.parse(arguments);
            if (parsed instanceof Map) {
                return parsed;
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }
}
