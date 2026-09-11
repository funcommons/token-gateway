package fun.commons.tokengateway.task.relay;

import com.alibaba.fastjson2.JSON;
import fun.commons.tokengateway.contract.DistributeRequest;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.TokenValidateRequest;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.spi.model.TaskStatus;
import fun.commons.tokengateway.task.billing.TaskBillingSaga;
import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.lotask.LotaskTaskView;
import fun.commons.tokengateway.task.lotask.RouteSnapshotCipher;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskNoMappingStore;
import fun.commons.tokengateway.task.state.TaskStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务面 create/poll 编排 (《06》§2 task.relay; 控制层决策 · 数据面执行).
 *
 * <p>create 管线 (《05》§5.1):
 * key 验证 (控制层) → route resolve 定价 (控制层, 先路由定价再计费) → 全额预扣
 * → 路由快照加密 → lotask4j submit (幂等键=task_no) → 失败全额退款 + 10004.
 *
 * <p>poll 管线 (《05》§5.3): key 验证 → task_no→lotask id 映射 → lotask get → 状态映射;
 * 终态返回存储结果, resources 一律转 sig 代理 URL (透传路径同样不泄漏上游 URL).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRelayOrchestrator {

    private static final char[] TASK_NO_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private final HttpTokenApi tokenApi;
    private final HttpChannelApi channelApi;
    private final fun.commons.tokengateway.rpc.AdapterSelector adapterSelector;
    private final fun.commons.tokengateway.rpc.TokenRouteClient tokenRouteClient;
    private final TaskBillingSaga billingSaga;
    private final LotaskTaskClient lotaskClient;
    private final RouteSnapshotCipher snapshotCipher;
    private final TaskNoMappingStore mappingStore;
    private final TaskMetaStore metaStore;
    private final fun.commons.tokengateway.task.ResourceUrlConverter resourceUrlConverter;
    private final TokenGatewayProperties props;

    /**
     * 创建任务 (同步返回 task_no; 异步执行在自写 Worker + lotask4j).
     *
     * @param modality  模态 (video/image/audio/tts, 即 lotask task_type)
     * @param apiKey    调用方凭证 (Bearer/x-api-key 已提取)
     * @param body      请求体 {model, params, input, notify_url}
     * @param traceId   链路 ID (X-Trace-Id 或生成)
     * @return {task_no, status=PENDING, poll_url}
     */
    public Mono<Map<String, Object>> create(String modality, String apiKey,
                                            Map<String, Object> body, String traceId) {
        return create(modality, apiKey, body, traceId, null);
    }

    /**
     * create (重载, #12): idempotencyKey 即接入方 Idempotency-Key, 优先作为 billing requestId
     * (接入方幂等键透传); amount=渠道分发响应的 priceQuote (全额语义).
     */
    public Mono<Map<String, Object>> create(String modality, String apiKey,
                                            Map<String, Object> body, String traceId,
                                            String idempotencyKey) {
        String model = body == null ? null : (String) body.get("model");
        if (model == null || model.isBlank()) {
            return Mono.error(new RelayException(400, ApiCode.REQUIRED_MISSING.getCode(),
                    "缺少必填参数: model"));
        }
        if (apiKey == null) {
            return Mono.error(new RelayException(401, ApiCode.UNAUTHORIZED.getCode(),
                    "缺少 bearer token"));
        }
        // billing requestId 必须数字 (MMagiX credit related_id BIGINT); 接入方数字幂等键优先
        String requestId = traceId != null && traceId.matches("\\d+") ? traceId
                : String.valueOf(System.currentTimeMillis() * 1000
                        + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000));
        final String idemKey = idempotencyKey;
        return tokenApi.validate(TokenValidateRequest.builder().apiKey(apiKey).model(model).build())
                .flatMap(tokenResp -> {
                    if (tokenResp == null || !tokenResp.isSuccess() || tokenResp.getData() == null
                            || !tokenResp.getData().isValid()) {
                        return Mono.error(new RelayException(401, "invalid token"));
                    }
                    TokenValidateVO token = tokenResp.getData();
                    return resolveRoute(token, model, idemKey, body)
                            .flatMap(channel -> submitWithSaga(modality, token, channel,
                                    model, body,
                                    idemKey != null ? idemKey : requestId,
                                    channel.getPriceQuote()));
                });
    }

    /**
     * 控制层 route resolve: 模型不同价不同, 先定价再预扣; 10400 语义透传 (同 LLM 面).
     * <p>G5: adapter=tokengo|openapi 时走 token-route resolve (data_json 契约字段映射),
     * 路由快照随 submit 载荷下发 Worker 的链路不变.
     */
    private Mono<DistributeVO> resolveRoute(TokenValidateVO token, String model, String idempotencyKey,
                                            Map<String, Object> body) {
        Map<String, Object> priceParams = new LinkedHashMap<>();
        if (body != null) {
            for (String k : new String[]{"size", "ratio", "resolution"}) {
                if (body.get(k) != null) {
                    priceParams.put(k, body.get(k));
                }
            }
        }
        if (adapterSelector.routeViaTokenRoute()) {
            return tokenRouteClient.resolve(model, null, 0, 0, null);
        }
        return channelApi.distribute(DistributeRequest.builder()
                        .tenantId(token.getTenantId())
                        .userId(token.getUserId())
                        .apiKeyId(token.getTokenId())
                        .groupId(token.getGroupId())
                        .model(model)
                        .params(priceParams)
                        .idempotencyKey(idempotencyKey).build())
                .flatMap(distResp -> {
                    if (distResp == null || !distResp.isSuccess() || distResp.getData() == null) {
                        String reason = distResp == null ? "no response"
                                : (distResp.getMessage() == null ? "unknown" : distResp.getMessage());
                        if (distResp != null && distResp.getCode() == ApiCode.NOT_FOUND.getCode()) {
                            return Mono.error(new RelayException(404, ApiCode.NOT_FOUND.getCode(),
                                    "模型不存在或无可用渠道: " + reason));
                        }
                        return Mono.error(new RelayException(502,
                                "channel distribute failed: " + reason));
                    }
                    return Mono.just(distResp.getData());
                });
    }

    private Mono<Map<String, Object>> submitWithSaga(String modality, TokenValidateVO token,
                                                     DistributeVO channel, String model,
                                                     Map<String, Object> body, String requestId,
                                                     Integer amount) {
        String ownerType = channel.getOwnerType() != null
                ? channel.getOwnerType().name() : "PLATFORM";
        return billingSaga.preConsumeFull(token, channel.getChannelId(), ownerType, model, requestId, amount)
                .flatMap(preConsumeId -> {
                    // issue #13: lotask 侧 task_type 粒度可配 (modality 默认 | model);
                    // timeouts/TaskMeta 同键解析, 网关 API 面 (poll_url 等) 维持模态
                    String submitTaskType = props.getTask().submitTaskTypeOf(modality, model);
                    String taskNo = generateTaskNo();
                    Map<String, Object> payload = buildPayload(model, body, channel);
                    String callbackUrl = props.getTask().getLotask().getWebhookCallbackUrl();
                    return lotaskClient.submit(submitTaskType, taskNo, payload, callbackUrl)
                            .flatMap(lotaskId -> {
                                Duration ttl = props.getTask().timeoutOf(submitTaskType)
                                        .plus(Duration.ofHours(24));
                                long deadline = System.currentTimeMillis()
                                        + props.getTask().timeoutOf(submitTaskType).toMillis();
                                Object notifyUrl = body.get("notify_url");
                                return mappingStore.put(taskNo, lotaskId, ttl)
                                        .then(metaStore.onCreated(taskNo,
                                                new TaskMetaStore.TaskMeta(lotaskId, preConsumeId,
                                                        submitTaskType,
                                                        notifyUrl == null ? null : notifyUrl.toString(),
                                                        deadline, channel.getApiKey()), ttl))
                                        .thenReturn(createdView(modality, taskNo));
                            })
                            .onErrorResume(e -> {
                                // submit 失败 → 全额退款, 不产生"扣了钱没任务" (《05》§11)
                                if (e instanceof RelayException re) {
                                    log.warn("[Task] submit 失败, 全额退款: taskNo={}, preConsumeId={}, err={}",
                                            taskNo, preConsumeId, re.getMessage());
                                }
                                return billingSaga.refundOnce(preConsumeId, "submit failed", requestId)
                                        .then(Mono.error(e instanceof RelayException re ? re
                                                : new RelayException(502,
                                                ApiCode.THIRD_PARTY_ERROR.getCode(),
                                                "task submit failed: " + e.getMessage())));
                            });
                });
    }

    /** 载荷: 业务参数 + notify_url + 加密路由快照 (R7 网关侧补偿, 平台只见密文). */
    private Map<String, Object> buildPayload(String model, Map<String, Object> body,
                                             DistributeVO channel) {
        Map<String, Object> routeSnapshot = new LinkedHashMap<>();
        routeSnapshot.put("baseUrl", channel.getBaseUrl());
        routeSnapshot.put("apiKey", channel.getApiKey());
        routeSnapshot.put("modelMapping", channel.getModelMapping());
        String encrypted = snapshotCipher.encrypt(JSON.toJSONString(routeSnapshot));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("params", body.get("params"));
        payload.put("input", body.get("input"));
        payload.put("notifyUrl", body.get("notify_url"));
        payload.put("routeSnapshot", encrypted);
        return payload;
    }

    /**
     * 轮询 (终态幂等: 优先读终态条目——SUCCEEDED 返回 sig 代理 URL, EXPIRED 返回超时钟判定;
     * 非终态走 lotask 查询; lotask 不可达 → 502, 调用方退避重试, 状态不变不触计费).
     */
    public Mono<Map<String, Object>> poll(String modality, String taskNo, String apiKey) {
        if (apiKey == null) {
            return Mono.error(new RelayException(401, ApiCode.UNAUTHORIZED.getCode(),
                    "缺少 bearer token"));
        }
        return tokenApi.validate(TokenValidateRequest.builder().apiKey(apiKey).build())
                .flatMap(tokenResp -> {
                    if (tokenResp == null || !tokenResp.isSuccess() || tokenResp.getData() == null
                            || !tokenResp.getData().isValid()) {
                        return Mono.error(new RelayException(401, "invalid token"));
                    }
                    return mappingStore.get(taskNo)
                            .switchIfEmpty(Mono.error(new RelayException(404,
                                    ApiCode.NOT_FOUND.getCode(), "任务不存在或映射已过期: " + taskNo)))
                            .flatMap(lotaskId -> metaStore.getTerminalResult(taskNo)
                                    .map(entry -> terminalPollView(taskNo, entry))
                                    .switchIfEmpty(Mono.defer(() -> lotaskClient.get(lotaskId)
                                            .map(view -> pollView(taskNo, view)))));
                });
    }

    /** 终态条目视图 (终态幂等, 不触 lotask; resources 已是 sig 代理 URL). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> terminalPollView(String taskNo,
                                                 com.alibaba.fastjson2.JSONObject entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_no", taskNo);
        out.put("status", entry.getString("status"));
        if (entry.get("result") != null) {
            out.put("result", entry.get("result"));
        }
        if (entry.get("error") != null) {
            out.put("error", entry.get("error"));
        }
        return out;
    }

    /**
     * OpenAI 协议同步封装 (POST /v1/images/sync; LLM 面 /v1/images/generations 已被 face-llm 占用): create + 轮询至终态.
     * <p>成功 → {created, data:[{url}]} (url=网关签名代理, 24h); 上游失败 → 502 + 上游错误消息;
     * 同步等待超时 (默认 60s) → 降级 {status=PROCESSING, task_no, poll_url} (HTTP 200, 接入方按 status 分流).
     * <p>仅支持 n=1 (任务面单图语义); size 原生透传 (gpt-image 3 档/auto), ratio 扩展 (9:16 等).
     */
    public Mono<Map<String, Object>> createImageGenerations(String apiKey, Map<String, Object> body,
                                                            String traceId, String idempotencyKey) {
        Object n = body == null ? null : body.get("n");
        if (n instanceof Number num && num.intValue() > 1) {
            return Mono.error(new RelayException(400, ApiCode.REQUIRED_MISSING.getCode(),
                    "仅支持 n=1 (任务面单图语义)"));
        }
        // 任务面契约: 业务参数在 body.params (buildPayload 取 params/input 两个子对象)
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("prompt", body == null ? null : body.get("prompt"));
        if (body != null && body.get("size") != null) {
            inner.put("size", body.get("size"));
        }
        if (body != null && body.get("ratio") != null) {
            inner.put("ratio", body.get("ratio"));
        }
        if (body != null && body.get("resolution") != null) {
            inner.put("resolution", body.get("resolution"));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", body == null ? null : body.get("model"));
        params.put("params", inner);
        java.time.Instant deadline = java.time.Instant.now().plus(GENERATIONS_SYNC_TIMEOUT);
        return create("image", apiKey, params, traceId, idempotencyKey)
                .flatMap(created -> {
                    String taskNo = String.valueOf(created.get("task_no"));
                    return pollUntilTerminal("image", taskNo, apiKey, deadline, GENERATIONS_POLL_INTERVAL);
                });
    }

    private static final java.time.Duration GENERATIONS_SYNC_TIMEOUT = java.time.Duration.ofSeconds(60);
    private static final java.time.Duration GENERATIONS_POLL_INTERVAL = java.time.Duration.ofSeconds(2);

    Mono<Map<String, Object>> pollUntilTerminal(String modality, String taskNo, String apiKey,
                                                        java.time.Instant deadline,
                                                        java.time.Duration interval) {
        return poll(modality, taskNo, apiKey)
                .flatMap(view -> {
                    String status = String.valueOf(view.get("status"));
                    if ("SUCCEEDED".equals(status)) {
                        return Mono.just(generationsSuccessBody(view));
                    }
                    if ("FAILED".equals(status) || "EXPIRED".equals(status)) {
                        Object error = view.get("error");
                        String msg = error instanceof Map<?, ?> m && m.get("message") != null
                                ? String.valueOf(m.get("message")) : "task " + status;
                        return Mono.error(new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(), msg));
                    }
                    if (java.time.Instant.now().isAfter(deadline)) {
                        return Mono.just(processingFallbackBody(taskNo));
                    }
                    return Mono.delay(interval).then(pollUntilTerminal(modality, taskNo, apiKey, deadline, interval));
                });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> generationsSuccessBody(Map<String, Object> pollView) {
        List<Map<String, Object>> data = new ArrayList<>();
        Object result = pollView.get("result");
        if (result instanceof Map<?, ?> r && r.get("resources") instanceof List<?> resources) {
            for (Object url : resources) {
                data.add(Map.of("url", String.valueOf(url)));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", java.time.Instant.now().getEpochSecond());
        out.put("data", data);
        return out;
    }

    private Map<String, Object> processingFallbackBody(String taskNo) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "PROCESSING");
        out.put("task_no", taskNo);
        out.put("poll_url", "/v1/images/" + taskNo);
        return out;
    }

    /** 轮询视图: 终态返回存储结果; resources 一律转代理 URL (永不透传上游原文). */
    private Map<String, Object> pollView(String taskNo, LotaskTaskView view) {
        TaskStatus status = TaskStateMapper.map(view.status());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_no", taskNo);
        out.put("status", status.name());
        if (status == TaskStatus.SUCCEEDED && view.result() != null) {
            // poll 透传路径同样转代理 URL (上游 URL 永不透传, 《05》§4; webhook 未达时兜底)
            out.put("result", resourceUrlConverter.convert(taskNo, view.result()));
        }
        if (status == TaskStatus.FAILED || status == TaskStatus.EXPIRED) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", view.errorCode());
            error.put("message", view.errorMessage());
            out.put("error", error);
        }
        return out;
    }

    private static Map<String, Object> createdView(String modality, String taskNo) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_no", taskNo);
        out.put("status", TaskStatus.PENDING.name());
        out.put("poll_url", "/v1/" + modality + "s/" + taskNo);
        return out;
    }

    /** task_no: T + yyMMddHHmmss + 8 位随机 (手册示例 T20260831...). */
    static String generateTaskNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder("T").append(ts);
        for (int i = 0; i < 8; i++) {
            sb.append(TASK_NO_ALPHABET[random.nextInt(TASK_NO_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
