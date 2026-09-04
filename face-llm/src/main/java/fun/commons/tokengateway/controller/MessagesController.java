package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.exception.RelayException;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.ModerationAuditRequest;
import fun.commons.tokengateway.format.AnthropicSseConverter;
import fun.commons.tokengateway.format.FormatConverter;
import fun.commons.tokengateway.relay.FailoverProperties;
import fun.commons.tokengateway.relay.ModerationSupport;
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
    private final FailoverProperties failoverProps;

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
                            .body((Object) invokeStream(prepared, sanitize(body, prepared), traceId,
                                    estPrompt, estCompletion)));
        }
        long startNs = System.nanoTime();
        return orchestrator.prepare(apiKey, model, estPrompt, estCompletion, userContent, traceId)
                .flatMap(prepared -> {
                    Map<String, Object> effectiveBody = sanitize(body, prepared);
                    AtomicBoolean settled = new AtomicBoolean();
                    // 轮换后指向当前生效的 prepared (终态退款/记录用)
                    AtomicReference<RelayOrchestrator.PreparedRequest> active = new AtomicReference<>(prepared);
                    List<String> failedChannels = new ArrayList<>();
                    return invokeNonStreamWithFailover(prepared, model, effectiveBody,
                            estPrompt, estCompletion, failedChannels, 1, active)
                            .flatMap(resp -> {
                                // 轮换后须结算 active (新预扣); 旧 prepared 已被 failover 内 refund 退款
                                RelayOrchestrator.PreparedRequest current = active.get();
                                // resp 已被 invoke 转换为 Anthropic shape, 统一按 Anthropic 提取
                                // (openAiToAnthropicResponse 会把 usage 映射成 input/output_tokens)
                                fun.commons.tokengateway.relay.TokenUsage u =
                                        fun.commons.tokengateway.relay.TokenUsageExtractor.fromAnthropic(resp);
                                int latency = elapsedMs(startNs);
                                settled.set(true);
                                orchestrator.settle(current, u.promptTokens(), u.completionTokens(), u.cachedTokens(),
                                        latency)
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
        // 发上游用渠道重映射后的 upstream_code, 客户端 model 仅用于路由/计费
        Map<String, Object> upstreamBody = UpstreamModelMapper.applyModelMapping(channel,
                isAnthropicUpstream ? body : formatConverter.anthropicToOpenAiBody(body));

        return webClientBuilder.build().post()
                .uri(url)
                .header(isAnthropicUpstream ? "x-api-key" : "Authorization",
                        isAnthropicUpstream ? channel.getApiKey() : "Bearer " + channel.getApiKey())
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(upstreamBody)
                .retrieve()
                .bodyToMono((Class<Map<String, Object>>) (Class<?>) Map.class)
                .map(raw -> {
                    // 软失败: 200 + 错误载荷按上游真实错误上抛 (issue #1 缺口 1, 转换前判定双协议通吃)
                    UpstreamErrorPolicy.throwIfSoftError(raw);
                    return isAnthropicUpstream ? raw : formatConverter.openAiToAnthropicResponse(raw);
                })
                .onErrorResume(e -> {
                    log.error("[Messages] 非流式上游调用失败 url={}, err={}", url, e.getMessage());
                    // 透传上游真实状态码, 不再恒 502 (issue #1 缺口 3)
                    return Mono.error(UpstreamErrorPolicy.wrap(e));
                });
    }

    /**
     * 非流式上游调用 (请求内轮换): 失败时记录渠道失败并换道重试, 至多 failover.max-attempts 次.
     * <p>active 同步指向当前生效的 prepared, 终态退款/记录由 caller 读取.
     */
    private Mono<Map<String, Object>> invokeNonStreamWithFailover(
            RelayOrchestrator.PreparedRequest prepared, String model, Map<String, Object> body,
            int estPrompt, int estCompletion, List<String> failedChannels, int attempt,
            AtomicReference<RelayOrchestrator.PreparedRequest> active) {
        active.set(prepared);
        return invokeNonStream(prepared.channel(), body)
                .onErrorResume(err -> {
                    if (!UpstreamErrorPolicy.isRetryable(err) || !failoverProps.shouldRetry(attempt)) {
                        // 终态失败: 健康计数由 AccessLogReporter.reportError 统一上报, 此处不重复
                        return Mono.error(err);
                    }
                    // 先换道, 换道成功才对旧渠道记一次失败: 轮换中止 (如无候选) 时该失败
                    // 由终态 AccessLogReporter.reportError 恰好计一次, 防中间轮 + 终态双计
                    failedChannels.add(prepared.channel().getChannelId());
                    log.warn("[Messages] 渠道失败, 轮换重试: attempt={}, model={}, channelId={}, excluded={}, err={}",
                            attempt, model, prepared.channel().getChannelId(), failedChannels, err.getMessage());
                    return orchestrator.failoverToNextChannel(prepared, model,
                                    estPrompt, estCompletion, failedChannels)
                            .onErrorResume(foErr -> {
                                log.error("[Messages] 渠道轮换失败, 按原错误返回: {}", foErr.getMessage());
                                return Mono.error(err);
                            })
                            .flatMap(next -> orchestrator.recordChannelFailure(prepared, model,
                                            UpstreamErrorPolicy.errorCodeOf(err), err.getMessage())
                                    .then(Mono.delay(Duration.ofMillis(
                                            failoverProps.withJitter(failoverProps.backoffMs(attempt)))))
                                    .then(invokeNonStreamWithFailover(next, model, body,
                                            estPrompt, estCompletion, failedChannels, attempt + 1, active)));
                });
    }

    private Flux<ServerSentEvent<String>> invokeStream(
            RelayOrchestrator.PreparedRequest prepared,
            Map<String, Object> body, String traceId, int estPrompt, int estCompletion) {
        long startNs = System.nanoTime();
        String model = String.valueOf(body.getOrDefault("model", ""));
        List<String> failedChannels = new ArrayList<>();
        // 各次轮换尝试共享的 "当前生效 prepared / usage 累加器"; 生命周期钩子只挂最外层, 终态时读取,
        // 避免内层尝试的完成信号穿透外层导致重复 settle/退款
        AtomicReference<RelayOrchestrator.PreparedRequest> active = new AtomicReference<>(prepared);
        AtomicReference<fun.commons.tokengateway.relay.StreamUsageAccumulator> activeAcc =
                new AtomicReference<>(new fun.commons.tokengateway.relay.StreamUsageAccumulator());
        return streamAttempt(prepared, body, failedChannels, 1, estPrompt, estCompletion, active, activeAcc)
                .doOnComplete(() -> {
                    int latency = elapsedMs(startNs);
                    RelayOrchestrator.PreparedRequest current = active.get();
                    fun.commons.tokengateway.relay.StreamUsageAccumulator usageAcc = activeAcc.get();
                    fun.commons.tokengateway.relay.TokenUsage u = usageAcc.hasUsage()
                            ? usageAcc.result() : estimateFallback(body);
                    orchestrator.settle(current, u.promptTokens(), u.completionTokens(), u.cachedTokens(), latency)
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
     * 已吐帧后失败不能重放 (Anthropic 客户端会看到两段回答), 只上报失败计数并按原错误终止.
     */
    private Flux<ServerSentEvent<String>> streamAttempt(
            RelayOrchestrator.PreparedRequest prepared,
            Map<String, Object> body, List<String> failedChannels, int attempt,
            int estPrompt, int estCompletion,
            AtomicReference<RelayOrchestrator.PreparedRequest> active,
            AtomicReference<fun.commons.tokengateway.relay.StreamUsageAccumulator> activeAcc) {
        String model = String.valueOf(body.getOrDefault("model", ""));
        fun.commons.tokengateway.relay.StreamUsageAccumulator usageAcc =
                new fun.commons.tokengateway.relay.StreamUsageAccumulator();
        active.set(prepared);
        activeAcc.set(usageAcc);
        AtomicBoolean emitted = new AtomicBoolean();
        DistributeVO channel = prepared.channel();
        boolean isAnthropicUpstream = channel.getProtocol() != null
                && "anthropic".equalsIgnoreCase(channel.getProtocol());

        Flux<ServerSentEvent<String>> upstream;
        if (isAnthropicUpstream) {
            body.put("stream", true);
            upstream = sseInvoker.invokeStreamAnthropicNative(channel,
                    UpstreamModelMapper.applyModelMapping(channel, body), usageAcc);
        } else {
            Map<String, Object> upstreamBody = UpstreamModelMapper.applyModelMapping(channel,
                    withIncludeUsage(formatConverter.anthropicToOpenAiBody(body)));
            upstreamBody.put("stream", true);
            AnthropicSseConverter converter = new AnthropicSseConverter();
            upstream = sseInvoker.invokeStream(channel, upstreamBody, converter, usageAcc);
        }
        return upstream
                .doOnNext(e -> emitted.set(true))
                .onErrorResume(err -> {
                    if (emitted.get()) {
                        // 已吐帧不能重放换道 (Anthropic 客户端会看到两段回答);
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
                    log.warn("[Messages] 渠道失败, 轮换重试: attempt={}, model={}, channelId={}, excluded={}, err={}",
                            attempt, model, channel.getChannelId(), failedChannels, err.getMessage());
                    return orchestrator.failoverToNextChannel(prepared, model,
                                    estPrompt, estCompletion, failedChannels)
                            .onErrorResume(foErr -> {
                                log.error("[Messages] 渠道轮换失败, 按原错误返回: {}", foErr.getMessage());
                                return Mono.error(err);
                            })
                            .flatMapMany(next -> orchestrator.recordChannelFailure(prepared, model,
                                            UpstreamErrorPolicy.errorCodeOf(err), err.getMessage())
                                    .then(Mono.delay(Duration.ofMillis(
                                            failoverProps.withJitter(failoverProps.backoffMs(attempt)))))
                                    .thenMany(streamAttempt(next, body, failedChannels,
                                            attempt + 1, estPrompt, estCompletion, active, activeAcc)));
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
