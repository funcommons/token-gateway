package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.exception.RelayException;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.ModerationAuditRequest;
import fun.commons.tokengateway.format.AnthropicSseConverter;
import fun.commons.tokengateway.format.FormatConverter;
import fun.commons.tokengateway.relay.ModerationSupport;
import fun.commons.tokengateway.relay.RelayOrchestrator;
import fun.commons.tokengateway.rpc.HttpModerationApi;
import fun.commons.tokengateway.upstream.SsePassthroughInvoker;
import fun.commons.tokengateway.util.ChatTokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Anthropic Messages 端点 (WebFlux 原生).
 *
 * <p>路径: POST /v1/messages (单一端点, body.stream 分发)
 * <p>鉴权: x-api-key 或 Authorization: Bearer 双头
 * <p>上游协议:
 * <ul>
 *   <li>anthropic → 原样透传 (含 cache_control)</li>
 *   <li>openai → anthropicToOpenAiBody 转换 + 响应 openAiToAnthropicResponse</li>
 * </ul>
 * <p>OpenAI 上游场景下 cache_control 必须拒绝 (anthropicToOpenAiBody 会丢, 显式 400).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MessagesController {

    private static final String ANTHROPIC_ENDPOINT = "/v1/messages";
    private static final String OPENAI_ENDPOINT = "/v1/chat/completions";
    private static final String REQUEST_PATH = "/v1/messages";

    private final RelayOrchestrator orchestrator;
    private final SsePassthroughInvoker sseInvoker;
    private final FormatConverter formatConverter;
    private final fun.commons.tokengateway.relay.AccessLogReporter accessLogReporter;
    private final HttpModerationApi moderationApi;
    private final WebClient.Builder webClientBuilder;

    @PostMapping("/v1/messages")
    public Mono<org.springframework.http.ResponseEntity<Object>> messages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestBody Map<String, Object> body
    ) {
        return Mono.deferContextual(cv -> doMessages(authorization, xApiKey, body,
                cv.getOrDefault(fun.commons.tokengateway.trace.TraceWebFilter.CONTEXT_KEY,
                        (String) null)));
    }

    private Mono<org.springframework.http.ResponseEntity<Object>> doMessages(
            String authorization, String xApiKey, Map<String, Object> body, String traceId) {
        validateBody(body);
        String apiKey = extractApiKey(authorization, xApiKey);
        String model = String.valueOf(body.get("model"));
        String userContent = RelayOrchestrator.extractUserContent(body);
        // 修复 P0: 真实估算 prompt tokens (旧代码传 0 → amount=0 → 绕过余额检查)
        int estPrompt = ChatTokenEstimator.estimatePromptTokens(body);
        int estCompletion = ChatTokenEstimator.estimateCompletionTokens(isStream(body));

        if (isStream(body)) {
            return orchestrator.prepare(apiKey, model, estPrompt, estCompletion, userContent, traceId)
                    .map(prepared -> org.springframework.http.ResponseEntity.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body((Object) invokeStream(prepared, sanitize(body, prepared), traceId)));
        }
        long startNs = System.nanoTime();
        return orchestrator.prepare(apiKey, model, estPrompt, estCompletion, userContent, traceId)
                .flatMap(prepared -> {
                    Map<String, Object> effectiveBody = sanitize(body, prepared);
                    AtomicBoolean settled = new AtomicBoolean();
                    return invokeNonStream(prepared.channel(), effectiveBody)
                            .flatMap(resp -> {
                                // resp 已被 invoke 转换为 Anthropic shape, 统一按 Anthropic 提取
                                // (openAiToAnthropicResponse 会把 usage 映射成 input/output_tokens)
                                fun.commons.tokengateway.relay.TokenUsage u =
                                        fun.commons.tokengateway.relay.TokenUsageExtractor.fromAnthropic(resp);
                                int latency = elapsedMs(startNs);
                                settled.set(true);
                                orchestrator.settle(prepared, u.promptTokens(), u.completionTokens(), u.cachedTokens(),
                                        latency)
                                        .flatMap(credit -> accessLogReporter.reportSuccess(
                                                prepared, model, REQUEST_PATH,
                                                u.promptTokens(), u.completionTokens(), u.cachedTokens(),
                                                credit, latency, traceId))
                                        .subscribe(
                                                v -> {},
                                                e -> log.warn("[Saga/settle+AccessLog] preConsumeId={}, err={}",
                                                        prepared.preConsumeId(), e.getMessage()));
                                return auditOutput(prepared, resp)
                                        .doOnError(err -> accessLogReporter.reportError(
                                                prepared, model, REQUEST_PATH, 500, latency,
                                                traceId).subscribe(
                                                        v2 -> {},
                                                        e -> log.warn("[AccessLog] err={}", e.getMessage())))
                                        .thenReturn(resp);
                            })
                            .map(resp -> org.springframework.http.ResponseEntity.ok()
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body((Object) resp))
                            .doOnError(err -> {
                                if (!settled.get()) {
                                    orchestrator.refund(prepared,
                                            "upstream failed: " + err.getMessage()).subscribe(
                                                    v -> {},
                                                    e -> log.warn("[Saga/refund] preConsumeId={}, err={}",
                                                            prepared.preConsumeId(), e.getMessage()));
                                    accessLogReporter.reportError(prepared, model, REQUEST_PATH,
                                            502, elapsedMs(startNs), traceId).subscribe(
                                                    v -> {},
                                                    e -> log.warn("[AccessLog] err={}", e.getMessage()));
                                }
                            });
                });
    }

    /**
     * 输出审查 (非流式). 违规 → RelayException(500), 不退款 (对齐单体);
     * 无文本内容 / RPC 失败 → fail-open 放行.
     */
    private Mono<Void> auditOutput(
            fun.commons.tokengateway.relay.RelayOrchestrator.PreparedRequest prepared,
            Map<String, Object> resp) {
        String content = ModerationSupport.extractAnthropicCompletionContent(resp);
        if (content.isBlank()) {
            return Mono.empty();
        }
        return moderationApi.audit(ModerationAuditRequest.builder()
                        .content(content)
                        .baseUrl(prepared.channel().getBaseUrl())
                        .apiKey(prepared.channel().getApiKey())
                        .protocol(prepared.channel().getProtocol() != null
                                ? prepared.channel().getProtocol() : "openai")
                        .tenantId(prepared.token() != null ? prepared.token().getTenantId() : null)
                        .requestId(prepared.requestId())
                        .build())
                .flatMap(ar -> {
                    if (ar == null || !ar.isSuccess() || ar.getData() == null) {
                        log.warn("[Moderation] audit RPC 失败, fail-open 放行");
                        return Mono.empty();
                    }
                    if (!ar.getData().isPassed()) {
                        return Mono.error(new RelayException(500,
                                fun.commons.tokengateway.framework.ApiCode.BUSINESS_RULE_ERROR.getCode(),
                                "输出内容包含违规信息"));
                    }
                    return Mono.empty();
                });
    }

    private static int elapsedMs(long startNs) {
        return (int) ((System.nanoTime() - startNs) / 1_000_000);
    }

    private static void validateBody(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new RelayException(400,
                    fun.commons.tokengateway.framework.ApiCode.REQUIRED_MISSING.getCode(), "请求体为空");
        }
        Object model = body.get("model");
        if (!(model instanceof String s) || s.isBlank()) {
            throw new RelayException(400,
                    fun.commons.tokengateway.framework.ApiCode.REQUIRED_MISSING.getCode(), "model 字段必填");
        }
        if (!(body.get("max_tokens") instanceof Number)) {
            throw new RelayException(400,
                    fun.commons.tokengateway.framework.ApiCode.REQUIRED_MISSING.getCode(), "max_tokens 字段必填");
        }
    }

    private static boolean isStream(Map<String, Object> body) {
        Object s = body.get("stream");
        if (s instanceof Boolean b) return b;
        return s instanceof String str && "true".equalsIgnoreCase(str);
    }

    private static String extractApiKey(String auth, String xApiKey) {
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        if (xApiKey != null && !xApiKey.isBlank()) {
            return xApiKey.trim();
        }
        return null;
    }

    /**
     * 出站 body 处理: MASK 脱敏 + tool_use/tool_result 配对规整 (严格上游如 MiniMax 2013).
     */
    private Map<String, Object> sanitize(Map<String, Object> body, RelayOrchestrator.PreparedRequest prepared) {
        Map<String, Object> outbound = fun.commons.tokengateway.relay.AnthropicToolChainSanitizer.sanitize(
                RelayOrchestrator.applyMask(body, prepared.moderationSanitized()));
        logBodyDiff(body, outbound);
        return outbound;
    }

    /**
     * 打印客户端入参与转发出参的尾部片段, 便于定位上游 400 (悬空 tool_use / MASK 脱敏) 引发的差异.
     */
    private static void logBodyDiff(Map<String, Object> inbound, Map<String, Object> outbound) {
        if (!log.isInfoEnabled()) {
            return;
        }
        String in = com.alibaba.fastjson2.JSON.toJSONString(inbound == null ? null : inbound.get("messages"));
        String out = com.alibaba.fastjson2.JSON.toJSONString(outbound == null ? null : outbound.get("messages"));
        log.info("[BodyDiff] inLen={}, outLen={}, changed={}", in.length(), out.length(), !in.equals(out));
        log.info("[BodyDiff] inbound  tail500={}", tail(in, 500));
        log.info("[BodyDiff] outbound tail500={}", tail(out, 500));
        if (!in.equals(out)) {
            int p = commonPrefixLen(in, out);
            log.info("[BodyDiff] 首处差异 offset={}, in={}, out={}",
                    p, window(in, p, 250), window(out, p, 250));
        }
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private static int commonPrefixLen(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static String window(String s, int from, int n) {
        if (from >= s.length()) {
            return "";
        }
        return s.substring(from, Math.min(s.length(), from + n));
    }

    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> invokeNonStream(DistributeVO channel, Map<String, Object> body) {
        boolean isAnthropicUpstream = channel.getProtocol() != null
                && "anthropic".equalsIgnoreCase(channel.getProtocol());

        String endpoint = isAnthropicUpstream ? ANTHROPIC_ENDPOINT : OPENAI_ENDPOINT;
        String url = channel.getBaseUrl().replaceAll("/+$", "") + endpoint;
        Map<String, Object> upstreamBody = isAnthropicUpstream
                ? body : formatConverter.anthropicToOpenAiBody(body);

        return webClientBuilder.build().post()
                .uri(url)
                .header(isAnthropicUpstream ? "x-api-key" : "Authorization",
                        isAnthropicUpstream ? channel.getApiKey() : "Bearer " + channel.getApiKey())
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(upstreamBody)
                .retrieve()
                .bodyToMono((Class<Map<String, Object>>) (Class<?>) Map.class)
                .map(raw -> isAnthropicUpstream ? raw : formatConverter.openAiToAnthropicResponse(raw))
                .onErrorResume(e -> {
                    log.error("[Messages] 非流式上游调用失败 url={}, err={}", url, e.getMessage());
                    return Mono.error(new RelayException(502,
                            "upstream failed: " + e.getMessage()));
                });
    }

    private Flux<ServerSentEvent<String>> invokeStream(
            fun.commons.tokengateway.relay.RelayOrchestrator.PreparedRequest prepared,
            Map<String, Object> body, String traceId) {
        long startNs = System.nanoTime();
        String model = String.valueOf(body.getOrDefault("model", ""));
        DistributeVO channel = prepared.channel();
        boolean isAnthropicUpstream = channel.getProtocol() != null
                && "anthropic".equalsIgnoreCase(channel.getProtocol());
        fun.commons.tokengateway.relay.StreamUsageAccumulator usageAcc =
                new fun.commons.tokengateway.relay.StreamUsageAccumulator();

        Flux<ServerSentEvent<String>> upstream;
        if (isAnthropicUpstream) {
            body.put("stream", true);
            upstream = sseInvoker.invokeStreamAnthropicNative(channel, body, usageAcc);
        } else {
            Map<String, Object> upstreamBody = withIncludeUsage(formatConverter.anthropicToOpenAiBody(body));
            upstreamBody.put("stream", true);
            AnthropicSseConverter converter = new AnthropicSseConverter();
            upstream = sseInvoker.invokeStream(channel, upstreamBody, converter, usageAcc);
        }
        return upstream
                .doOnComplete(() -> {
                    int latency = elapsedMs(startNs);
                    fun.commons.tokengateway.relay.TokenUsage u = usageAcc.hasUsage()
                            ? usageAcc.result() : estimateFallback(body);
                    orchestrator.settle(prepared, u.promptTokens(), u.completionTokens(), u.cachedTokens(), latency)
                            .flatMap(credit -> accessLogReporter.reportSuccess(prepared, model, REQUEST_PATH,
                                    u.promptTokens(), u.completionTokens(), u.cachedTokens(), credit, latency, traceId))
                            .subscribe(
                                    v -> {},
                                    e -> log.warn("[Saga/settle+AccessLog] preConsumeId={}, err={}",
                                            prepared.preConsumeId(), e.getMessage()));
                })
                .doOnError(err -> {
                    orchestrator.refund(prepared,
                            "upstream stream error: " + err.getMessage()).subscribe(
                                    v -> {},
                                    e -> log.warn("[Saga/refund] preConsumeId={}, err={}",
                                            prepared.preConsumeId(), e.getMessage()));
                    accessLogReporter.reportError(prepared, model, REQUEST_PATH,
                            502, elapsedMs(startNs), traceId).subscribe(
                                    v -> {},
                                    e -> log.warn("[AccessLog] err={}", e.getMessage()));
                })
                .doOnCancel(() -> {
                    orchestrator.refund(prepared, "client cancelled").subscribe(
                            v -> {},
                            e -> log.warn("[Saga/refund] preConsumeId={}, err={}",
                                    prepared.preConsumeId(), e.getMessage()));
                    accessLogReporter.reportError(prepared, model, REQUEST_PATH,
                            499, elapsedMs(startNs), traceId).subscribe(
                                    v -> {},
                                    e -> log.warn("[AccessLog] err={}", e.getMessage()));
                });
    }

    /**
     * OpenAI 渠道: 注入 stream_options.include_usage=true 让上游末帧带真实 usage (合并不覆盖).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withIncludeUsage(Map<String, Object> body) {
        Map<String, Object> copy = new java.util.HashMap<>(body);
        Object existing = copy.get("stream_options");
        Map<String, Object> merged = existing instanceof Map<?, ?> m
                ? new java.util.HashMap<>((Map<String, Object>) m) : new java.util.HashMap<>();
        merged.put("include_usage", true);
        copy.put("stream_options", merged);
        return copy;
    }

    /**
     * 上游未吐 usage 帧的估算兜底: prompt 按消息文本 len/4, completion 固定 256 (对齐单体).
     */
    private static fun.commons.tokengateway.relay.TokenUsage estimateFallback(Map<String, Object> body) {
        String content = RelayOrchestrator.extractUserContent(body);
        int prompt = content == null ? 0 : Math.max(1, content.length() / 4);
        return new fun.commons.tokengateway.relay.TokenUsage(prompt, 256, 0);
    }
}
