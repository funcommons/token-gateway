package fun.commons.tokengateway.format;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FormatConverter 全分支表驱动测试 (OpenAI ↔ Anthropic ↔ Gemini 双向转换).
 */
class FormatConverterTest {

    private final FormatConverter converter = new FormatConverter();

    // ---------- openaiToAnthropic ----------

    @Test
    void openaiToAnthropic_nullReturnsEmpty() {
        assertThat(converter.openaiToAnthropic(null)).isEmpty();
    }

    @Test
    void openaiToAnthropic_extractsStringSystemAndDefaultsMaxTokens() {
        Map<String, Object> out = converter.openaiToAnthropic(Map.of(
                "model", "claude-sonnet-4-5",
                "messages", List.of(
                        Map.of("role", "system", "content", "be nice"),
                        Map.of("role", "user", "content", "hi"))));
        assertThat(out).containsEntry("model", "claude-sonnet-4-5")
                .containsEntry("max_tokens", 4096)
                .containsEntry("system", "be nice");
        assertThat((List<?>) out.get("messages")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) out.get("messages")).get(0)).get("role")).isEqualTo("user");
    }

    @Test
    void openaiToAnthropic_systemBlockListJoinedAndParamsCopied() {
        Map<String, Object> out = converter.openaiToAnthropic(Map.of(
                "temperature", 0.5, "top_p", 0.9, "stop_sequences", List.of("END"),
                "max_tokens", 128,
                "messages", List.of(Map.of("role", "system", "content",
                        List.of(Map.of("type", "text", "text", "a"), Map.of("type", "text", "text", "b"))))));
        assertThat(out).containsEntry("system", "a\nb")
                .containsEntry("temperature", 0.5)
                .containsEntry("top_p", 0.9)
                .containsEntry("max_tokens", 128)
                .containsEntry("stop_sequences", List.of("END"));
    }

    @Test
    void openaiToAnthropic_messageContentVariants() {
        Map<String, Object> asst = converter.openaiMessageToAnthropic(
                Map.of("role", "assistant", "content", "sure"));
        assertThat(asst.get("role")).isEqualTo("assistant");
        assertThat(asst.get("content")).isEqualTo("sure");

        Map<String, Object> blocks = converter.openaiMessageToAnthropic(
                Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", "x"))));
        assertThat(blocks.get("content")).isInstanceOf(List.class);

        Map<String, Object> blank = converter.openaiMessageToAnthropic(Map.of("role", "user"));
        assertThat(blank.get("content")).isEqualTo("");
        // 非 assistant 角色一律归一为 user
        assertThat(converter.openaiMessageToAnthropic(Map.of("role", "tool", "content", "r")).get("role"))
                .isEqualTo("user");
    }

    // ---------- openaiToGemini ----------

    @Test
    void openaiToGemini_nullReturnsEmpty() {
        assertThat(converter.openaiToGemini(null)).isEmpty();
    }

    @Test
    void openaiToGemini_systemAndRoleMapping() {
        Map<String, Object> out = converter.openaiToGemini(Map.of(
                "messages", List.of(
                        Map.of("role", "system", "content", "sys"),
                        Map.of("role", "user", "content", "q"),
                        Map.of("role", "assistant", "content", "a"),
                        Map.of("role", "user"))));
        assertThat(out.get("systemInstruction"))
                .isEqualTo(Map.of("parts", List.of(Map.of("text", "sys"))));
        List<?> contents = (List<?>) out.get("contents");
        assertThat(contents).hasSize(3);
        assertThat(((Map<?, ?>) contents.get(1)).get("role")).isEqualTo("model");
        // content 缺失 → 空 text part
        assertThat(((Map<?, ?>) contents.get(2)).get("parts")).isEqualTo(List.of(Map.of("text", "")));
    }

    // ---------- anthropicToOpenAIResponse ----------

    @Test
    void anthropicToOpenAIResponse_nullReturnsEmpty() {
        assertThat(converter.anthropicToOpenAIResponse(null)).isEmpty();
    }

    @Test
    void anthropicToOpenAIResponse_fullShapeAndUsage() {
        Map<String, Object> out = converter.anthropicToOpenAIResponse(Map.of(
                "id", "msg_1", "model", "claude-x",
                "content", List.of(Map.of("type", "text", "text", "he"),
                        Map.of("type", "text", "text", "llo"),
                        Map.of("type", "other")),
                "stop_reason", "max_tokens",
                "usage", Map.of("input_tokens", 10, "output_tokens", 5,
                        "cache_read_input_tokens", 4)));
        assertThat(out).containsEntry("id", "anthropic-msg_1")
                .containsEntry("object", "chat.completion")
                .containsEntry("model", "claude-x");
        Map<?, ?> choice = (Map<?, ?>) ((List<?>) out.get("choices")).get(0);
        assertThat(choice.get("finish_reason")).isEqualTo("length");
        assertThat(((Map<?, ?>) choice.get("message")).get("content")).isEqualTo("hello");
        Map<?, ?> usage = (Map<?, ?>) out.get("usage");
        assertThat(usage.get("prompt_tokens")).isEqualTo(10);
        assertThat(usage.get("completion_tokens")).isEqualTo(5);
        assertThat(usage.get("total_tokens")).isEqualTo(15);
        assertThat(((Map<?, ?>) usage.get("prompt_tokens_details")).get("cached_tokens")).isEqualTo(4);
    }

    @Test
    void anthropicToOpenAIResponse_stopReasonAndUsageVariants() {
        // stop_reason 缺失 → stop; tool_use → tool_calls; end_turn/stop_sequence → stop; refusal → content_filter (issue #18); 未知 → stop
        for (var e : Map.of("end_turn", "stop", "stop_sequence", "stop",
                "tool_use", "tool_calls", "refusal", "content_filter", "weird", "stop").entrySet()) {
            List<?> choices = (List<?>) converter.anthropicToOpenAIResponse(
                    Map.of("stop_reason", e.getKey())).get("choices");
            assertThat(((Map<?, ?>) choices.get(0)).get("finish_reason")).isEqualTo(e.getValue());
        }
        Map<String, Object> noUsage = converter.anthropicToOpenAIResponse(Map.of());
        assertThat(noUsage).doesNotContainKey("usage");
        assertThat(String.valueOf(noUsage.get("id"))).startsWith("chatcmpl-anthropic-");
        // usage 里 cache=0 → 不带 details
        Map<?, ?> usage = (Map<?, ?>) converter.anthropicToOpenAIResponse(
                Map.of("usage", Map.of("input_tokens", "3", "output_tokens", 1))).get("usage");
        assertThat(usage.get("total_tokens")).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(usage.containsKey("prompt_tokens_details")).isFalse();
    }

    @Test
    void anthropicToOpenAIResponse_cacheCreationNotLost_issue26() {
        // issue #26 口径定版: cache_creation_input_tokens 不再并入 cached,
        // 转换为 prompt_tokens_details.cache_creation_tokens (网关扩展键) 供 fromOpenAi 回收
        Map<String, Object> out = converter.anthropicToOpenAIResponse(Map.of(
                "usage", Map.of("input_tokens", 36, "output_tokens", 16,
                        "cache_read_input_tokens", 128, "cache_creation_input_tokens", 64)));
        Map<?, ?> usage = (Map<?, ?>) out.get("usage");
        Map<?, ?> details = (Map<?, ?>) usage.get("prompt_tokens_details");
        assertThat(details.get("cached_tokens")).isEqualTo(128);
        assertThat(details.get("cache_creation_tokens")).isEqualTo(64);
        // 回收链路: fromOpenAi 能读回 cacheCreation 细分
        var u = fun.commons.tokengateway.relay.TokenUsageExtractor.fromOpenAi(out);
        org.assertj.core.api.Assertions.assertThat(u.cachedTokens()).isEqualTo(128L);
        org.assertj.core.api.Assertions.assertThat(u.cacheCreationTokens()).isEqualTo(64L);
    }

    @Test
    void anthropicToOpenAIResponse_onlyCacheCreationStillEmitsDetails_issue26() {
        // 只带 creation 不带 read → details 仍输出 (含扩展键, 不含 cached_tokens)
        Map<?, ?> usage = (Map<?, ?>) converter.anthropicToOpenAIResponse(Map.of(
                "usage", Map.of("input_tokens", 10, "output_tokens", 5,
                        "cache_creation_input_tokens", 64))).get("usage");
        Map<?, ?> details = (Map<?, ?>) usage.get("prompt_tokens_details");
        assertThat(details.get("cache_creation_tokens")).isEqualTo(64);
        org.assertj.core.api.Assertions.assertThat(details.containsKey("cached_tokens")).isFalse();
    }

    // ---------- issue #18: OpenAI → Anthropic tools 链路 ----------

    @Test
    void openaiToAnthropic_toolsAndToolChoiceAndStopConverted() {
        Map<String, Object> out = converter.openaiToAnthropic(Map.of(
                "model", "claude-x",
                "max_completion_tokens", 512,   // issue #18 (R2): 新版 SDK 键回退
                "stop", "END",                  // issue #18: stop → stop_sequences
                "tools", List.of(Map.of(
                        "type", "function",
                        "function", Map.of("name", "get_weather", "description", "查天气",
                                "parameters", Map.of("type", "object")))),
                "tool_choice", "required",
                "messages", List.of(Map.of("role", "user", "content", "北京天气"))));
        assertThat(out).containsEntry("max_tokens", 512)
                .containsEntry("stop_sequences", List.of("END"))
                .containsEntry("tool_choice", "any");
        Map<?, ?> tool = (Map<?, ?>) ((List<?>) out.get("tools")).get(0);
        assertThat(tool.get("name")).isEqualTo("get_weather");
        assertThat(tool.get("description")).isEqualTo("查天气");
        assertThat(tool.get("input_schema")).isEqualTo(Map.of("type", "object"));
        assertThat(tool.containsKey("function")).isFalse();

        // 具名 tool_choice: {type:function,function:{name}} → {type:tool,name}
        Map<String, Object> named = converter.openaiToAnthropic(Map.of(
                "messages", List.of(),
                "tool_choice", Map.of("type", "function",
                        "function", Map.of("name", "get_weather"))));
        assertThat(named.get("tool_choice"))
                .isEqualTo(Map.of("type", "tool", "name", "get_weather"));
    }

    @Test
    void openaiToAnthropic_toolHistoryConverted() {
        Map<String, Object> assistant = new java.util.HashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of(Map.of(
                "id", "call_1", "type", "function",
                "function", Map.of("name", "get_weather", "arguments", "{\"city\":\"北京\"}"))));
        Map<String, Object> out = converter.openaiToAnthropic(Map.of(
                "messages", List.of(
                        Map.of("role", "user", "content", "北京天气"),
                        assistant,
                        Map.of("role", "tool", "tool_call_id", "call_1", "content", "晴"),
                        Map.of("role", "user", "content", "谢谢"))));

        List<?> msgs = (List<?>) out.get("messages");
        assertThat(msgs).hasSize(3);
        // assistant: content = [tool_use 块], arguments JSON 串已解析为 input 对象
        Map<?, ?> asst = (Map<?, ?>) msgs.get(1);
        assertThat(asst.get("role")).isEqualTo("assistant");
        Map<?, ?> toolUse = (Map<?, ?>) ((List<?>) asst.get("content")).get(0);
        assertThat(toolUse.get("type")).isEqualTo("tool_use");
        assertThat(toolUse.get("id")).isEqualTo("call_1");
        assertThat(toolUse.get("name")).isEqualTo("get_weather");
        assertThat(toolUse.get("input")).isEqualTo(Map.of("city", "北京"));
        // 连续 tool 消息 → tool_result 块并入紧随 user 轮 (前置), tool_call_id 不丢
        Map<?, ?> user = (Map<?, ?>) msgs.get(2);
        assertThat(user.get("role")).isEqualTo("user");
        List<?> blocks = (List<?>) user.get("content");
        assertThat(blocks).hasSize(2);
        assertThat(((Map<?, ?>) blocks.get(0)).get("type")).isEqualTo("tool_result");
        assertThat(((Map<?, ?>) blocks.get(0)).get("tool_use_id")).isEqualTo("call_1");
        assertThat(((Map<?, ?>) blocks.get(1)).get("type")).isEqualTo("text");
        assertThat(((Map<?, ?>) blocks.get(1)).get("text")).isEqualTo("谢谢");
    }

    @Test
    void anthropicToOpenAIResponse_toolUseToToolCalls() {
        Map<String, Object> out = converter.anthropicToOpenAIResponse(Map.of(
                "id", "msg_t", "model", "claude-x",
                "content", List.of(
                        Map.of("type", "text", "text", "查一下"),
                        Map.of("type", "tool_use", "id", "toolu_1",
                                "name", "get_weather", "input", Map.of("city", "北京"))),
                "stop_reason", "tool_use"));
        Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) ((List<?>) out.get("choices")).get(0)).get("message");
        assertThat(message.get("content")).isEqualTo("查一下");
        Map<?, ?> call = (Map<?, ?>) ((List<?>) message.get("tool_calls")).get(0);
        assertThat(call.get("id")).isEqualTo("toolu_1");
        assertThat(call.get("type")).isEqualTo("function");
        assertThat(((Map<?, ?>) call.get("function")).get("name")).isEqualTo("get_weather");
        assertThat(((Map<?, ?>) call.get("function")).get("arguments"))
                .isEqualTo(com.alibaba.fastjson2.JSON.toJSONString(Map.of("city", "北京")));
        assertThat(((Map<?, ?>) ((List<?>) out.get("choices")).get(0)).get("finish_reason"))
                .isEqualTo("tool_calls");

        // 仅 tool_use 无文本 → content=null (对齐 OpenAI 官方语义)
        Map<String, Object> toolOnly = converter.anthropicToOpenAIResponse(Map.of(
                "content", List.of(Map.of("type", "tool_use", "id", "t2",
                        "name", "f", "input", Map.of())),
                "stop_reason", "tool_use"));
        Map<?, ?> toolOnlyMsg = (Map<?, ?>) ((Map<?, ?>) ((List<?>) toolOnly.get("choices")).get(0)).get("message");
        assertThat(toolOnlyMsg.get("content")).isNull();
        assertThat((List<?>) toolOnlyMsg.get("tool_calls")).hasSize(1);
    }

    @Test
    void anthropicToOpenAiBody_urlImageKept() {
        // issue #18 (R3): source.type=url 的图不再静默丢弃, 直传 image_url.url
        Map<String, Object> out = converter.anthropicToOpenAiBody(Map.of(
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "image", "source",
                                Map.of("type", "url", "url", "https://img.example.com/a.png")))))));
        Map<?, ?> msg = (Map<?, ?>) ((List<?>) out.get("messages")).get(0);
        Map<?, ?> part = (Map<?, ?>) ((List<?>) msg.get("content")).get(0);
        assertThat(part.get("type")).isEqualTo("image_url");
        assertThat(((Map<?, ?>) part.get("image_url")).get("url"))
                .isEqualTo("https://img.example.com/a.png");
    }

    // ---------- geminiToOpenAIResponse ----------

    @Test
    void geminiToOpenAIResponse_nullReturnsEmpty() {
        assertThat(converter.geminiToOpenAIResponse(null, "m")).isEmpty();
    }

    @Test
    void geminiToOpenAIResponse_partsConcatAndUsage() {
        Map<String, Object> out = converter.geminiToOpenAIResponse(Map.of(
                "candidates", List.of(Map.of("content", Map.of("parts", List.of(
                        Map.of("text", "a"), Map.of("text", "b"), Map.of("x", 1))))),
                "usageMetadata", Map.of("promptTokenCount", 7, "candidatesTokenCount", 3)),
                "gemini-pro");
        assertThat(out).containsEntry("role", "assistant").containsEntry("model", "gemini-pro")
                .containsEntry("content", "ab");
        assertThat(((Map<?, ?>) out.get("usage")).get("total_tokens")).isEqualTo(10);
    }

    @Test
    void geminiToOpenAIResponse_missingStructuresFallBackEmpty() {
        assertThat(converter.geminiToOpenAIResponse(Map.of(), "m").get("content")).isEqualTo("");
        assertThat(converter.geminiToOpenAIResponse(
                Map.of("candidates", List.of()), "m").get("content")).isEqualTo("");
        assertThat(converter.geminiToOpenAIResponse(
                Map.of("candidates", List.of(Map.of("content", Map.of()))), "m").get("content")).isEqualTo("");
        assertThat(converter.geminiToOpenAIResponse(
                Map.of("candidates", List.of(Map.of("content", Map.of("parts", List.of())))), "m")
                .get("content")).isEqualTo("");
        // totalTokenCount 缺失 → prompt+completion 兜底
        Map<?, ?> usage = (Map<?, ?>) converter.geminiToOpenAIResponse(Map.of(
                "usageMetadata", Map.of("promptTokenCount", 2, "candidatesTokenCount", 2)), "m").get("usage");
        assertThat(usage.get("total_tokens")).isEqualTo(4);
    }

    // ---------- anthropicToOpenAiBody ----------

    @Test
    void anthropicToOpenAiBody_nullReturnsEmpty() {
        assertThat(converter.anthropicToOpenAiBody(null)).isEmpty();
    }

    @Test
    void anthropicToOpenAiBody_systemStringAndSimpleMessages() {
        Map<String, Object> out = converter.anthropicToOpenAiBody(Map.of(
                "model", "gpt-x", "temperature", 0.1, "top_p", 0.2, "max_tokens", 99,
                "stop_sequences", List.of("S"),
                "system", "be brief",
                "messages", List.of(
                        Map.of("role", "user", "content", "hi"),
                        Map.of("role", "assistant", "content", "yo"))));
        assertThat(out).containsEntry("model", "gpt-x").containsEntry("stop", List.of("S"))
                .containsEntry("max_tokens", 99);
        List<?> msgs = (List<?>) out.get("messages");
        assertThat(msgs).hasSize(3);
        assertThat(((Map<?, ?>) msgs.get(0)).get("role")).isEqualTo("system");
        assertThat(((Map<?, ?>) msgs.get(2)).get("content")).isEqualTo("yo");
    }

    @Test
    void anthropicToOpenAiBody_systemBlocksAndBlankFiltered() {
        List<?> msgs = (List<?>) converter.anthropicToOpenAiBody(Map.of(
                "system", List.of(Map.of("type", "text", "text", "s1"), Map.of("type", "x"),
                        Map.of("type", "text", "text", "s2")),
                "messages", List.of(Map.of("role", "user", "content", "q")))).get("messages");
        assertThat(msgs).hasSize(2);
        assertThat(((Map<?, ?>) msgs.get(0)).get("content")).isEqualTo("s1\ns2");
        // system 为空白块数组 → 不产 system 消息
        List<?> noSys = (List<?>) converter.anthropicToOpenAiBody(
                java.util.Map.<String, Object>of(
                        "system", List.of(Map.of("type", "text")), "messages", List.of())
        ).get("messages");
        assertThat(noSys).isEmpty();
    }

    @Test
    void anthropicToOpenAiBody_imageAndToolBlocks() {
        Map<String, Object> body = Map.of(
                "messages", List.of(
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", "look"),
                                Map.of("type", "image", "source", Map.of(
                                        "type", "base64", "media_type", "image/png", "data", "QUJD")),
                                Map.of("type", "image", "source", Map.of("type", "url")))),
                        Map.of("role", "assistant", "content", List.of(
                                Map.of("type", "text", "text", "calling"),
                                Map.of("type", "tool_use", "id", "t1", "name", "f", "input", Map.of("k", "v")))),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "tool_result", "tool_use_id", "t1",
                                        "content", List.of(Map.of("type", "text", "text", "res"))),
                                Map.of("type", "text", "text", "and then")))));
        List<?> msgs = (List<?>) converter.anthropicToOpenAiBody(body).get("messages");

        // user 消息: text + base64 image (url source 丢弃)
        Map<?, ?> user = (Map<?, ?>) msgs.get(0);
        List<?> userContent = (List<?>) user.get("content");
        assertThat(userContent).hasSize(2);
        assertThat(((Map<?, ?>) userContent.get(1)).get("type")).isEqualTo("image_url");
        assertThat((String) ((Map<?, ?>) ((Map<?, ?>) userContent.get(1)).get("image_url")).get("url"))
                .isEqualTo("data:image/png;base64,QUJD");

        // assistant 消息 → tool_calls, arguments 为 JSON 串
        Map<?, ?> asst = (Map<?, ?>) msgs.get(1);
        assertThat(asst.get("role")).isEqualTo("assistant");
        Map<?, ?> toolCall = (Map<?, ?>) ((List<?>) asst.get("tool_calls")).get(0);
        assertThat(((Map<?, ?>) toolCall.get("function")).get("name")).isEqualTo("f");
        assertThat((String) ((Map<?, ?>) toolCall.get("function")).get("arguments")).contains("\"k\"");

        // tool_result → role=tool 消息 + 剩余 text 补 user 消息
        Map<?, ?> toolMsg = (Map<?, ?>) msgs.get(2);
        assertThat(toolMsg.get("role")).isEqualTo("tool");
        assertThat(toolMsg.get("tool_call_id")).isEqualTo("t1");
        assertThat(toolMsg.get("content")).isEqualTo("res");
        Map<?, ?> trailing = (Map<?, ?>) msgs.get(3);
        assertThat(trailing.get("role")).isEqualTo("user");
        assertThat(trailing.get("content")).isEqualTo("and then");
    }

    @Test
    void anthropicToOpenAiBody_toolResultWithImageAndBadInput() {
        Map<String, Object> body = Map.of(
                "messages", List.of(
                        Map.of("role", "assistant", "content", List.of(
                                Map.of("type", "tool_use", "name", "f"))),   // 无 id/input → 兜底
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "tool_result", "tool_use_id", "t",
                                        "content", "plain string"),
                                Map.of("type", "image", "source", Map.of(
                                        "type", "base64", "media_type", "image/jpeg", "data", "ZA")),
                                Map.of("type", "tool_use", "name", "g", "input", Map.of())))));
        List<?> msgs = (List<?>) converter.anthropicToOpenAiBody(body).get("messages");
        // assistant: 无文本只有 tool_use → content=null + tool_calls
        Map<?, ?> asst = (Map<?, ?>) msgs.get(0);
        assertThat(asst.get("content")).isNull();
        assertThat((String) ((Map<?, ?>) ((List<?>) asst.get("tool_calls")).get(0)).get("id")).startsWith("call_");
        // tool_result(字符串) 先拆为 role=tool 消息 (msgs[1])
        Map<?, ?> toolMsg = (Map<?, ?>) msgs.get(1);
        assertThat(toolMsg.get("role")).isEqualTo("tool");
        assertThat(toolMsg.get("content")).isEqualTo("plain string");
        // 剩余 image + (被忽略的非 assistant tool_use) → 补 content 数组的 user 消息 (msgs[2])
        Map<?, ?> user = (Map<?, ?>) msgs.get(2);
        assertThat(user.get("role")).isEqualTo("user");
        assertThat(user.get("content")).isInstanceOf(List.class);
        assertThat((List<?>) user.get("content")).hasSize(1); // 只有 image_url part
    }

    @Test
    void anthropicToOpenAiBody_toolsAndToolChoice() {
        Map<String, Object> out = converter.anthropicToOpenAiBody(Map.of(
                "tools", List.of(Map.of("name", "f", "description", "d", "input_schema", Map.of("type", "object"))),
                "tool_choice", Map.of("type", "tool", "name", "f"),
                "messages", List.of()));
        Map<?, ?> tool = (Map<?, ?>) ((List<?>) out.get("tools")).get(0);
        assertThat(tool.get("type")).isEqualTo("function");
        Map<?, ?> function = (Map<?, ?>) tool.get("function");
        assertThat(function.get("name")).isEqualTo("f");
        assertThat(function.get("description")).isEqualTo("d");
        assertThat(function.get("parameters")).isEqualTo(Map.of("type", "object"));
        Map<?, ?> choice = (Map<?, ?>) out.get("tool_choice");
        assertThat(((Map<?, ?>) choice.get("function")).get("name")).isEqualTo("f");

        // tool_choice 字符串/映射各分支
        assertThat(converter.anthropicToOpenAiBody(
                Map.of("tool_choice", "auto", "messages", List.of())).get("tool_choice")).isEqualTo("auto");
        assertThat(converter.anthropicToOpenAiBody(
                Map.of("tool_choice", "any", "messages", List.of())).get("tool_choice")).isEqualTo("required");
        assertThat(converter.anthropicToOpenAiBody(
                Map.of("tool_choice", Map.of("type", "auto"), "messages", List.of()))
                .get("tool_choice")).isEqualTo("auto");
        assertThat(converter.anthropicToOpenAiBody(
                Map.of("tool_choice", Map.of("type", "any"), "messages", List.of()))
                .get("tool_choice")).isEqualTo("required");
        Object passthrough = Map.of("type", "mystery");
        assertThat(converter.anthropicToOpenAiBody(
                Map.of("tool_choice", passthrough, "messages", List.of())).get("tool_choice"))
                .isSameAs(passthrough);
        assertThat(converter.anthropicToOpenAiBody(
                Map.of("tool_choice", 42, "messages", List.of())).get("tool_choice")).isEqualTo(42);
    }

    // ---------- openAiToAnthropicResponse ----------

    @Test
    void openAiToAnthropicResponse_nullReturnsEmpty() {
        assertThat(converter.openAiToAnthropicResponse(null)).isEmpty();
    }

    @Test
    void openAiToAnthropicResponse_textAndToolUse() {
        Map<String, Object> out = converter.openAiToAnthropicResponse(Map.of(
                "id", "chatcmpl-1", "model", "gpt-x",
                "choices", List.of(Map.of(
                        "message", Map.of("content", "hello",
                                "tool_calls", List.of(Map.of(
                                        "id", "c1", "function",
                                        Map.of("name", "f", "arguments", "{\"k\":1}")))),
                        "finish_reason", "tool_calls")),
                "usage", Map.of("prompt_tokens", 3, "completion_tokens", 4)));
        assertThat(out).containsEntry("id", "chatcmpl-1").containsEntry("type", "message")
                .containsEntry("role", "assistant").containsEntry("model", "gpt-x")
                .containsEntry("stop_reason", "tool_use");
        List<?> blocks = (List<?>) out.get("content");
        assertThat(((Map<?, ?>) blocks.get(0)).get("text")).isEqualTo("hello");
        Map<?, ?> toolUse = (Map<?, ?>) blocks.get(1);
        assertThat(toolUse.get("id")).isEqualTo("c1");
        assertThat(toolUse.get("name")).isEqualTo("f");
        assertThat(toolUse.get("input")).isEqualTo(Map.of("k", 1));
        Map<?, ?> usage2 = (Map<?, ?>) out.get("usage");
        assertThat(usage2.get("input_tokens")).isEqualTo(3);
        assertThat(usage2.get("output_tokens")).isEqualTo(4);
    }

    @Test
    void openAiToAnthropicResponse_breakdownCarried_issue26() {
        // issue #26 计费口径修复: cached_tokens → cache_read_input_tokens (Anthropic 标准键),
        // reasoning/audio → 网关扩展键 (仅在有值时注入); fromAnthropic 能读回全部细分
        Map<String, Object> out = converter.openAiToAnthropicResponse(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("content", "hi"), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 36, "completion_tokens", 16,
                        "prompt_tokens_details", Map.of("cached_tokens", 128, "audio_tokens", 6),
                        "completion_tokens_details", Map.of("reasoning_tokens", 9, "audio_tokens", 2))));
        Map<?, ?> usage = (Map<?, ?>) out.get("usage");
        assertThat(usage.get("input_tokens")).isEqualTo(36);
        assertThat(usage.get("output_tokens")).isEqualTo(16);
        assertThat(usage.get("cache_read_input_tokens")).isEqualTo(128);
        assertThat(usage.get("reasoning_tokens")).isEqualTo(9L);
        assertThat(usage.get("audio_tokens")).isEqualTo(8L);   // 两侧 audio 求和
        // 回收链路: Messages 非流式链 (OpenAI 上游) fromAnthropic 不再丢细分
        var u = fun.commons.tokengateway.relay.TokenUsageExtractor.fromAnthropic(out);
        org.assertj.core.api.Assertions.assertThat(u.cachedTokens()).isEqualTo(128L);
        org.assertj.core.api.Assertions.assertThat(u.reasoningTokens()).isEqualTo(9L);
        org.assertj.core.api.Assertions.assertThat(u.audioTokens()).isEqualTo(8L);
    }

    @Test
    void openAiToAnthropicResponse_noBreakdownNoExtensionKeys_issue26() {
        // 上游无细分 → 扩展键不注入 (决议约束: null 不加键)
        Map<?, ?> usage = (Map<?, ?>) converter.openAiToAnthropicResponse(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", "hi"), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 3, "completion_tokens", 4))).get("usage");
        org.assertj.core.api.Assertions.assertThat(usage.containsKey("cache_read_input_tokens")).isFalse();
        org.assertj.core.api.Assertions.assertThat(usage.containsKey("reasoning_tokens")).isFalse();
        org.assertj.core.api.Assertions.assertThat(usage.containsKey("audio_tokens")).isFalse();
    }

    @Test
    void openAiToAnthropicResponse_finishReasonAndFallbacks() {
        // finish_reason 映射 + 坏 arguments + 缺 id + 空 content 兜底
        Map<String, Object> out = converter.openAiToAnthropicResponse(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("tool_calls", List.of(
                                Map.of("function", Map.of("name", "f", "arguments", "not-json")))),
                        "finish_reason", "content_filter"))));
        assertThat(out.get("stop_reason")).isEqualTo("refusal");
        List<?> blocks = (List<?>) out.get("content");
        assertThat(((Map<?, ?>) blocks.get(0)).get("type")).isEqualTo("tool_use");
        assertThat(String.valueOf(blocks.get(0).toString())).contains("toolu_");
        assertThat(((Map<?, ?>) blocks.get(0)).get("input")).isEqualTo(Map.of());
        assertThat(String.valueOf(out.get("id"))).startsWith("msg_");

        for (var e : Map.of("stop", "end_turn", "length", "max_tokens",
                "whatever", "end_turn").entrySet()) {
            Map<?, ?> o = converter.openAiToAnthropicResponse(Map.of(
                    "choices", List.of(Map.of("finish_reason", e.getKey()))));
            assertThat(o.get("stop_reason")).isEqualTo(e.getValue());
        }
        // 无 choices / choices 空 → 空 text 块兜底
        assertThat(converter.openAiToAnthropicResponse(Map.of("choices", List.of())).get("content"))
                .isEqualTo(List.of(Map.of("type", "text", "text", "")));
        assertThat(converter.openAiToAnthropicResponse(Map.of()).get("stop_reason")).isNull();
    }

    // ---------- flattenForModeration ----------

    @Test
    void flattenForModeration_allBlockTypes() {
        String out = converter.flattenForModeration(Map.of(
                "system", "sys",
                "messages", List.of(
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", "t1"),
                                Map.of("type", "image", "source", Map.of("media_type", "image/png")),
                                Map.of("type", "image"),
                                Map.of("type", "tool_use", "input", Map.of("a", 1)),
                                Map.of("type", "tool_result", "content",
                                        List.of(Map.of("type", "text", "text", "nested"))),
                                Map.of("type", "mystery"), "not-a-map"),
                                "content2", java.util.Collections.EMPTY_LIST),
                        Map.of("role", "assistant", "content", "plain"))));
        assertThat(out).contains("sys").contains("t1")
                .contains("[image:media_type=image/png]").contains("[image:media_type=unknown]")
                .contains("[tool_use:{a=1}]").contains("nested").contains("plain");
        assertThat(converter.flattenForModeration(null)).isEmpty();
    }

    // ---------- hasCacheControl ----------

    @Test
    void hasCacheControl_detectionPoints() {
        assertThat(converter.hasCacheControl(null)).isFalse();
        assertThat(converter.hasCacheControl(Map.of("system",
                Map.of("cache_control", Map.of())))).isTrue();
        assertThat(converter.hasCacheControl(Map.of("system", List.of(
                Map.of("type", "text", "cache_control", "x"))))).isTrue();
        assertThat(converter.hasCacheControl(Map.of("messages", List.of(
                Map.of("content", List.of(Map.of("cache_control", "y"))))))).isTrue();
        assertThat(converter.hasCacheControl(Map.of("messages", List.of(
                Map.of("content", Map.of("cache_control", "z")))))).isTrue();
        assertThat(converter.hasCacheControl(Map.of("messages", List.of(
                Map.of("content", "clean"),
                Map.of("content", List.of(Map.of("type", "text", "text", "no cache"))))))
        ).isFalse();
    }

    // ---------- toAnthropicError ----------

    @Test
    void toAnthropicError_typeByStatusAndExplicit() {
        assertThat(converter.toAnthropicError(400, "bad", null))
                .isEqualTo(Map.of("type", "error",
                        "error", Map.of("type", "invalid_request_error", "message", "bad")));
        for (var e : Map.of(400, "invalid_request_error", 401, "authentication_error",
                403, "permission_error", 404, "not_found_error", 413, "request_too_large",
                429, "rate_limit_error", 529, "overloaded_error", 500, "api_error",
                418, "invalid_request_error").entrySet()) {
            Map<?, ?> out = converter.toAnthropicError(e.getKey(), "m", null);
            assertThat(((Map<?, ?>) out.get("error")).get("type")).isEqualTo(e.getValue());
        }
        Map<?, ?> explicit = (Map<?, ?>) converter.toAnthropicError(400, "m", "custom_type").get("error");
        assertThat(explicit.get("type")).isEqualTo("custom_type");
        assertThat(((Map<?, ?>) converter.toAnthropicError(400, null, null).get("error")).get("message"))
                .isEqualTo("");
    }
}
