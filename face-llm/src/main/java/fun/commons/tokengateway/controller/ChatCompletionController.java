package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.exception.RelayException;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.ModerationAuditRequest;
import fun.commons.tokengateway.format.FormatConverter;
import fun.commons.tokengateway.format.OpenAiSseConverter;
import fun.commons.tokengateway.relay.FailoverProperties;
import fun.commons.tokengateway.relay.ModerationSupport;
import fun.commons.tokengateway.contract.SettleRequest;
import fun.commons.tokengateway.relay.RelayOrchestrator;
import fun.commons.tokengateway.relay.UpstreamErrorPolicy;
import fun.commons.tokengateway.rpc.HttpModerationApi;
import fun.commons.tokengateway.upstream.SsePassthroughInvoker;
import fun.commons.tokengateway.upstream.UpstreamModelMapper;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private final FailoverProperties failoverProps;

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
                                    traceId, estPrompt, estCompletion)));
        }
        long startNs = System.nanoTime();
        return orchestrator.prepare(apiKey, model, estPrompt, estCompletion, userContent, traceId)
                .flatMap(prepared -> {
                    Map<String, Object> effectiveBody = RelayOrchestrator.applyMask(body, prepared.moderationSanitized());
                    AtomicBoolean settled = new AtomicBoolean();
                    // 轮换后指向当前生效的 prepared (终态退款/记录用)
                    AtomicReference<RelayOrchestrator.PreparedRequest> active = new AtomicReference<>(prepared);
                    List<String> failedChannels = new ArrayList<>();
                    List<SettleRequest.AttemptDetail> lossAttempts = new ArrayList<>();
                    return invokeNonStreamWithFailover(prepared, model, effectiveBody,
                            estPrompt, estCompletion, failedChannels, 1, active, lossAttempts)
                            .flatMap(resp -> {
                                // 修复 P0: 轮换后须结算 active (新预扣); 旧 prepared 已被
                                // failover 内 refund 退款, 用它会漏结算新预扣 (PENDING 挂账 + 用户白嫖)
                                RelayOrchestrator.PreparedRequest current = active.get();
                                // resp 已被 invoke 转换为 OpenAI shape, 统一按 OpenAI 提取
                                // (anthropicToOpenAIResponse 会把 usage 映射成 prompt/completion_tokens)
                                fun.commons.tokengateway.relay.TokenUsage u =
                                        fun.commons.tokengateway.relay.TokenUsageExtractor.fromOpenAi(resp);
                                int latency = elapsedMs(startNs);
                                settled.set(true);
                                orchestrator.settle(current, u.promptTokens(), u.completionTokens(), u.cachedTokens(),
                                        latency, lossAttempts)
                                        .flatMap(credit -> accessLogReporter.reportSuccess(
                                                current, model, REQUEST_PATH,
                                                u.promptTokens(), u.completionTokens(), u.cachedTokens(),
                                                credit, latency, traceId))
                                        .subscribe(
                                                v -> {},
                                                e -> log.warn("[Saga/settle+AccessLog] preConsumeId={}, err={}",
                                                        current.preConsumeId(), e.getMessage()));
                                return auditOutput(current, resp)
                                        // 审核失败/内容违规非渠道责任: 记访问日志但不触渠道健康 (issue #1 缺口 2)
                                        .doOnError(err -> accessLogReporter.reportErrorWithoutHealth(
                                                current, model, REQUEST_PATH, 500, latency,
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
                                    RelayOrchestrator.PreparedRequest current = active.get();
                                    orchestrator.refund(current,
                                            "upstream failed: " + err.getMessage()).subscribe(
                                                    v -> {},
                                                    e -> log.warn("[Saga/refund] preConsumeId={}, err={}",
                                                            current.preConsumeId(), e.getMessage()));
                                    accessLogReporter.reportError(current, model, REQUEST_PATH,
                                            UpstreamErrorPolicy.httpStatusOf(err),
                                            elapsedMs(startNs), traceId).subscribe(
                                                    v -> {},
                                                    e -> log.warn("[AccessLog] err={}", e.getMessage()));
                                }
                            })
                            .doOnCancel(() -> {
                                // 客户端取消: 退款 + 499 落日志 (镜像流式 doOnCancel; 取消即漏退款)
                                if (!settled.get()) {
                                    RelayOrchestrator.PreparedRequest current = active.get();
                                    orchestrator.refund(current, "client cancelled").subscribe(
                                            v -> {},
                                            e -> log.warn("[Saga/refund] preConsumeId={}, err={}",
                                                    current.preConsumeId(), e.getMessage()));
                                    accessLogReporter.reportError(current, model, REQUEST_PATH,
                                            499, elapsedMs(startNs), traceId).subscribe(
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
        // 发上游用渠道重映射后的 upstream_code, 客户端 model 仅用于路由/计费
        Map<String, Object> upstreamBody = UpstreamModelMapper.applyModelMapping(channel,
                isAnthropic ? formatConverter.openaiToAnthropic(body) : body);

        return webClientBuilder.build().post()
                .uri(url)
                .header(isAnthropic ? "x-api-key" : "Authorization",
                        isAnthropic ? channel.getApiKey() : "Bearer " + channel.getApiKey())
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(upstreamBody)
                .retrieve()
                .bodyToMono((Class<Map<String, Object>>) (Class<?>) Map.class)
                .map(raw -> {
                    // 软失败: 200 + 错误载荷按上游真实错误上抛 (issue #1 缺口 1, 转换前判定双协议通吃)
                    UpstreamErrorPolicy.throwIfSoftError(raw);
                    return isAnthropic ? formatConverter.anthropicToOpenAIResponse(raw) : raw;
                })
                .onErrorResume(e -> {
                    log.error("[ChatCompletion] 非流式上游调用失败 url={}, err={}", url, e.getMessage());
                    // 透传上游真实状态码, 不再恒 502 (issue #1 缺口 3)
                    return Mono.error(UpstreamErrorPolicy.wrap(e));
                });
    }

    private Flux<ServerSentEvent<String>> invokeUpstreamStream(
            RelayOrchestrator.PreparedRequest prepared,
            Map<String, Object> body, String traceId, int estPrompt, int estCompletion) {
        long startNs = System.nanoTime();
        String model = String.valueOf(body.getOrDefault("model", ""));
        List<String> failedChannels = new ArrayList<>();
        List<SettleRequest.AttemptDetail> lossAttempts = new ArrayList<>();
        // 各次轮换尝试共享的 "当前生效 prepared / usage 累加器"; 生命周期钩子只挂最外层, 终态时读取,
        // 避免内层尝试的完成信号穿透外层导致重复 settle/退款
        AtomicReference<RelayOrchestrator.PreparedRequest> active =
                new AtomicReference<>(prepared);
        AtomicReference<fun.commons.tokengateway.relay.StreamUsageAccumulator> activeAcc =
                new AtomicReference<>(new fun.commons.tokengateway.relay.StreamUsageAccumulator());
        return streamAttempt(prepared, body, failedChannels, 1, estPrompt, estCompletion, active, activeAcc, lossAttempts)
                .doOnComplete(() -> {
                    int latency = elapsedMs(startNs);
                    RelayOrchestrator.PreparedRequest current = active.get();
                    fun.commons.tokengateway.relay.StreamUsageAccumulator usageAcc = activeAcc.get();
                    fun.commons.tokengateway.relay.TokenUsage u = usageAcc.hasUsage()
                            ? usageAcc.result() : estimateFallback(body);
                    log.info("[ChatCompletion/stream] traceId={}, model={}, hasUsage={}, prompt={}, completion={}, cached={}",
                            traceId, model, usageAcc.hasUsage(),
                            u.promptTokens(), u.completionTokens(), u.cachedTokens());
                    orchestrator.settle(current, u.promptTokens(), u.completionTokens(), u.cachedTokens(), latency, lossAttempts)
                            .flatMap(credit -> accessLogReporter.reportSuccess(current, model, REQUEST_PATH,
                                    u.promptTokens(), u.completionTokens(), u.cachedTokens(), credit, latency, traceId))
                            .subscribe(
                                    v -> {},
                                    e -> log.warn("[Saga/settle+AccessLog] preConsumeId={}, err={}",
                                            current.preConsumeId(), e.getMessage()));
                })
                .doOnError(err -> {
                    RelayOrchestrator.PreparedRequest current = active.get();
                    orchestrator.refund(current,
                            "upstream stream error: " + err.getMessage()).subscribe(
                                    v -> {},
                                    e -> log.warn("[Saga/refund] preConsumeId={}, err={}",
                                            current.preConsumeId(), e.getMessage()));
                    accessLogReporter.reportError(current, model, REQUEST_PATH,
                            UpstreamErrorPolicy.httpStatusOf(err),
                            elapsedMs(startNs), traceId).subscribe(
                                    v -> {},
                                    e -> log.warn("[AccessLog] err={}", e.getMessage()));
                })
                .doOnCancel(() -> {
                    RelayOrchestrator.PreparedRequest current = active.get();
                    orchestrator.refund(current, "client cancelled").subscribe(
                            v -> {},
                            e -> log.warn("[Saga/refund] preConsumeId={}, err={}",
                                    current.preConsumeId(), e.getMessage()));
                    accessLogReporter.reportError(current, model, REQUEST_PATH,
                            499, elapsedMs(startNs), traceId).subscribe(
                                    v -> {},
                                    e -> log.warn("[AccessLog] err={}", e.getMessage()));
                });
    }

    /**
     * 单次流式尝试 (请求内轮换): 失败且未向客户端吐帧时, 记录渠道失败并换道重试;
     * 已吐帧后失败不能重放 (用户会看到两段回答), 按原错误终止.
     */
    private Flux<ServerSentEvent<String>> streamAttempt(
            RelayOrchestrator.PreparedRequest prepared,
            Map<String, Object> body, List<String> failedChannels, int attempt,
            int estPrompt, int estCompletion,
            AtomicReference<RelayOrchestrator.PreparedRequest> active,
            AtomicReference<fun.commons.tokengateway.relay.StreamUsageAccumulator> activeAcc,
            List<SettleRequest.AttemptDetail> lossAttempts) {
        String model = String.valueOf(body.getOrDefault("model", ""));
        fun.commons.tokengateway.relay.StreamUsageAccumulator usageAcc =
                new fun.commons.tokengateway.relay.StreamUsageAccumulator();
        active.set(prepared);
        activeAcc.set(usageAcc);
        AtomicBoolean emitted = new AtomicBoolean();
        DistributeVO channel = prepared.channel();
        // 发上游用渠道重映射后的 upstream_code, 客户端 model 仅用于路由/计费
        Map<String, Object> upstreamBody = UpstreamModelMapper.applyModelMapping(channel, withIncludeUsage(body));
        Flux<ServerSentEvent<String>> upstream = isAnthropicChannel(channel)
                ? invokeAnthropicUpstream(channel, upstreamBody, usageAcc)
                : sseInvoker.invokeStream(channel, upstreamBody, usageAcc);
        return upstream
                .doOnNext(e -> emitted.set(true))
                .onErrorResume(err -> {
                    if (emitted.get()) {
                        // 已吐帧不能重放换道 (用户会看到两段回答);
                        // 终态健康计数由 AccessLogReporter.reportError 统一上报, 此处不重复
                        return Flux.error(err);
                    }
                    if (!UpstreamErrorPolicy.isRetryable(err) || !failoverProps.shouldRetry(attempt)) {
                        // 终态失败: 健康计数由 AccessLogReporter.reportError 统一上报, 此处不重复
                        return Flux.error(err);
                    }
                    // 先换道, 换道成功才对旧渠道记一次失败: 轮换中止 (如无候选) 时该失败
                    // 由终态 AccessLogReporter.reportError 恰好计一次, 防中间轮 + 终态双计
                    failedChannels.add(channel.getChannelId());
                    lossAttempts.add(SettleRequest.AttemptDetail.builder()
                            .sequence(attempt)
                            .channelId(channel.getChannelId())
                            .model(model)
                            .errorClass(UpstreamErrorPolicy.errorCodeOf(err))
                            .billed(err instanceof UpstreamErrorPolicy.SoftUpstreamException)
                            .build());
                    log.warn("[ChatCompletion/stream] 渠道失败, 轮换重试: attempt={}, model={}, channelId={}, excluded={}, err={}",
                            attempt, model, channel.getChannelId(), failedChannels, err.getMessage());
                    return orchestrator.failoverToNextChannel(prepared, model,
                                    estPrompt, estCompletion, failedChannels)
                            .onErrorResume(foErr -> {
                                log.error("[ChatCompletion/stream] 渠道轮换失败, 按原错误返回: {}", foErr.getMessage());
                                return Mono.error(err);
                            })
                            .flatMapMany(next -> orchestrator.recordChannelFailure(prepared, model,
                                            UpstreamErrorPolicy.errorCodeOf(err), err.getMessage())
                                    .then(Mono.delay(Duration.ofMillis(
                                            failoverProps.withJitter(failoverProps.backoffMs(attempt)))))
                                    .thenMany(streamAttempt(next, body, failedChannels,
                                            attempt + 1, estPrompt, estCompletion, active, activeAcc,
                                            lossAttempts)));
                });
    }

    /**
     * 非流式上游调用 (请求内轮换): 失败时记录渠道失败并换道重试, 至多 failover.max-attempts 次.
     * <p>active 同步指向当前生效的 prepared, 终态退款/记录由 caller 读取.
     */
    private Mono<Map<String, Object>> invokeNonStreamWithFailover(
            RelayOrchestrator.PreparedRequest prepared,
            String model, Map<String, Object> body,
            int estPrompt, int estCompletion, List<String> failedChannels, int attempt,
            AtomicReference<RelayOrchestrator.PreparedRequest> active,
            List<SettleRequest.AttemptDetail> lossAttempts) {
        active.set(prepared);
        return invokeUpstreamNonStream(prepared.channel(), body)
                .onErrorResume(err -> {
                    if (!UpstreamErrorPolicy.isRetryable(err) || !failoverProps.shouldRetry(attempt)) {
                        // 终态失败: 健康计数由 AccessLogReporter.reportError 统一上报, 此处不重复
                        return Mono.error(err);
                    }
                    // 先换道, 换道成功才对旧渠道记一次失败: 轮换中止 (如无候选) 时该失败
                    // 由终态 AccessLogReporter.reportError 恰好计一次, 防中间轮 + 终态双计
                    failedChannels.add(prepared.channel().getChannelId());
                    lossAttempts.add(SettleRequest.AttemptDetail.builder()
                            .sequence(attempt)
                            .channelId(prepared.channel().getChannelId())
                            .model(model)
                            .errorClass(UpstreamErrorPolicy.errorCodeOf(err))
                            .billed(err instanceof UpstreamErrorPolicy.SoftUpstreamException)
                            .build());
                    log.warn("[ChatCompletion] 渠道失败, 轮换重试: attempt={}, model={}, channelId={}, excluded={}, err={}",
                            attempt, model, prepared.channel().getChannelId(), failedChannels, err.getMessage());
                    return orchestrator.failoverToNextChannel(prepared, model,
                                    estPrompt, estCompletion, failedChannels)
                            .onErrorResume(foErr -> {
                                log.error("[ChatCompletion] 渠道轮换失败, 按原错误返回: {}", foErr.getMessage());
                                return Mono.error(err);
                            })
                            .flatMap(next -> orchestrator.recordChannelFailure(prepared, model,
                                            UpstreamErrorPolicy.errorCodeOf(err), err.getMessage())
                                    .then(Mono.delay(Duration.ofMillis(
                                            failoverProps.withJitter(failoverProps.backoffMs(attempt)))))
                                    .then(invokeNonStreamWithFailover(next, model, body,
                                            estPrompt, estCompletion, failedChannels, attempt + 1, active,
                                            lossAttempts)));
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

