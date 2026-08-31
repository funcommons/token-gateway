package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.ModerationAuditRequest;
import fun.commons.tokengateway.format.FormatConverter;
import fun.commons.tokengateway.format.OpenAiSseConverter;
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
 * OpenAI Chat Completions 端点 (WebFlux 原生).
 *
 * <p>路径: POST /v1/chat/completions
 * <p>分流: body.stream=true → Flux<ServerSentEvent>; 否则 → Mono<Map>.
 * <p>鉴权: Authorization: Bearer 或 x-api-key 双头.
 * <p>token 校验 + 渠道路由 委托到 {@link RelayOrchestrator}, 本类只关注上游协议分发与转换.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatCompletionController {

    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String REQUEST_PATH = "/v1/chat/completions";

    private final RelayOrchestrator orchestrator;
    private final SsePassthroughInvoker sseInvoker;
    private final FormatConverter formatConverter;
    private final fun.commons.tokengateway.relay.AccessLogReporter accessLogReporter;
    private final HttpModerationApi moderationApi;
    private final WebClient.Builder webClientBuilder;

    @PostMapping(value = "/v1/chat/completions")
    public Mono<org.springframework.http.ResponseEntity<Object>> complete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestBody Map<String, Object> body
    ) {
        return Mono.deferContextual(cv -> doComplete(authorization, xApiKey, body,
                cv.getOrDefault(fun.commons.tokengateway.trace.TraceWebFilter.CONTEXT_KEY,
                        (String) null)));
    }

    private Mono<org.springframework.http.ResponseEntity<Object>> doComplete(
            String authorization, String xApiKey, Map<String, Object> body, String traceId) {
        String apiKey = extractApiKey(authorization, xApiKey);
        String model = resolveModel(body);
        String userContent = RelayOrchestrator.extractUserContent(body);
        // 修复 P0: 真实估算 prompt tokens (旧代码传 0 → amount=0 → 绕过余额检查)
        int estPrompt = ChatTokenEstimator.estimatePromptTokens(body);
        int estCompletion = ChatTokenEstimator.estimateCompletionTokens(isStream(body));
        if (isStream(body)) {
            return orchestrator.prepare(apiKey, model, estPrompt, estCompletion, userContent, traceId)
                    .map(prepared -> org.springframework.http.ResponseEntity.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body((Object) invokeUpstreamStream(prepared, RelayOrchestrator.applyMask(body, prepared.moderationSanitized()),
                                    traceId)));
        }
        long startNs = System.nanoTime();
        return orchestrator.prepare(apiKey, model, estPrompt, estCompletion, userContent, traceId)
                .flatMap(prepared -> {
                    Map<String, Object> effectiveBody = RelayOrchestrator.applyMask(body, prepared.moderationSanitized());
                    AtomicBoolean settled = new AtomicBoolean();
                    return invokeUpstreamNonStream(prepared.channel(), effectiveBody)
                            .flatMap(resp -> {
                                // resp 已被 invoke 转换为 OpenAI shape, 统一按 OpenAI 提取
                                // (anthropicToOpenAIResponse 会把 usage 映射成 prompt/completion_tokens)
                                fun.commons.tokengateway.relay.TokenUsage u =
                                        fun.commons.tokengateway.relay.TokenUsageExtractor.fromOpenAi(resp);
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
        String content = ModerationSupport.extractOpenAiCompletionContent(resp);
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

    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> invokeUpstreamNonStream(DistributeVO channel, Map<String, Object> body) {
        boolean isAnthropic = channel.getProtocol() != null
                && "anthropic".equalsIgnoreCase(channel.getProtocol());
        String endpoint = isAnthropic ? "/v1/messages" : "/v1/chat/completions";
        String url = channel.getBaseUrl().replaceAll("/+$", "") + endpoint;
        Map<String, Object> upstreamBody = isAnthropic
                ? formatConverter.openaiToAnthropic(body) : body;

        return webClientBuilder.build().post()
                .uri(url)
                .header(isAnthropic ? "x-api-key" : "Authorization",
                        isAnthropic ? channel.getApiKey() : "Bearer " + channel.getApiKey())
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(upstreamBody)
                .retrieve()
                .bodyToMono((Class<Map<String, Object>>) (Class<?>) Map.class)
                .map(raw -> isAnthropic ? formatConverter.anthropicToOpenAIResponse(raw) : raw)
                .onErrorResume(e -> {
                    log.error("[ChatCompletion] 非流式上游调用失败 url={}, err={}", url, e.getMessage());
                    return Mono.error(new RelayException(502, "upstream failed: " + e.getMessage()));
                });
    }

    private Flux<ServerSentEvent<String>> invokeUpstreamStream(
            fun.commons.tokengateway.relay.RelayOrchestrator.PreparedRequest prepared,
            Map<String, Object> body, String traceId) {
        long startNs = System.nanoTime();
        String model = String.valueOf(body.getOrDefault("model", ""));
        DistributeVO channel = prepared.channel();
        fun.commons.tokengateway.relay.StreamUsageAccumulator usageAcc =
                new fun.commons.tokengateway.relay.StreamUsageAccumulator();
        Flux<ServerSentEvent<String>> upstream = isAnthropicChannel(channel)
                ? invokeAnthropicUpstream(channel, body, usageAcc)
                : sseInvoker.invokeStream(channel, withIncludeUsage(body), usageAcc);
        return upstream
                .doOnComplete(() -> {
                    int latency = elapsedMs(startNs);
                    fun.commons.tokengateway.relay.TokenUsage u = usageAcc.hasUsage()
                            ? usageAcc.result() : estimateFallback(body);
                    log.info("[ChatCompletion/stream] traceId={}, model={}, hasUsage={}, prompt={}, completion={}, cached={}",
                            traceId, model, usageAcc.hasUsage(),
                            u.promptTokens(), u.completionTokens(), u.cachedTokens());
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

    private Flux<ServerSentEvent<String>> invokeAnthropicUpstream(
            DistributeVO channel, Map<String, Object> openaiBody,
            fun.commons.tokengateway.relay.StreamUsageAccumulator usageAcc) {
        Map<String, Object> anthropicBody = formatConverter.openaiToAnthropic(openaiBody);
        anthropicBody.put("stream", true);
        log.info("[Relay/Stream] OpenAI→Anthropic: channelId={}, model={}",
                channel.getChannelId(), anthropicBody.get("model"));
        return sseInvoker.invokeStreamAnthropic(channel, anthropicBody, new OpenAiSseConverter(), usageAcc);
    }

    private static boolean isAnthropicChannel(DistributeVO channel) {
        return channel.getProtocol() != null
                && "anthropic".equalsIgnoreCase(channel.getProtocol());
    }

    private static boolean isStream(Map<String, Object> body) {
        Object s = body.get("stream");
        if (s instanceof Boolean b) return b;
        return s instanceof String str && "true".equalsIgnoreCase(str);
    }

    private static String resolveModel(Map<String, Object> body) {
        Object m = body.get("model");
        if (m instanceof String s && !s.isBlank()) return s;
        return DEFAULT_MODEL;
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
}

