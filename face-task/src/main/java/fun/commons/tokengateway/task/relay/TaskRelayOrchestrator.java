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
 * 终态返回存储结果 (M2.5c 起 resources 转 sig 代理 URL, 当前透传原文并标注).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRelayOrchestrator {

    private static final char[] TASK_NO_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private final HttpTokenApi tokenApi;
    private final HttpChannelApi channelApi;
    private final TaskBillingSaga billingSaga;
    private final LotaskTaskClient lotaskClient;
    private final RouteSnapshotCipher snapshotCipher;
    private final TaskNoMappingStore mappingStore;
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
        String model = body == null ? null : (String) body.get("model");
        if (model == null || model.isBlank()) {
            return Mono.error(new RelayException(400, ApiCode.REQUIRED_MISSING.getCode(),
                    "缺少必填参数: model"));
        }
        if (apiKey == null) {
            return Mono.error(new RelayException(401, ApiCode.UNAUTHORIZED.getCode(),
                    "缺少 bearer token"));
        }
        String requestId = traceId != null ? traceId : java.util.UUID.randomUUID().toString();
        return tokenApi.validate(TokenValidateRequest.builder().apiKey(apiKey).model(model).build())
                .flatMap(tokenResp -> {
                    if (tokenResp == null || !tokenResp.isSuccess() || tokenResp.getData() == null
                            || !tokenResp.getData().isValid()) {
                        return Mono.error(new RelayException(401, "invalid token"));
                    }
                    TokenValidateVO token = tokenResp.getData();
                    return resolveRoute(token, model)
                            .flatMap(channel -> submitWithSaga(modality, token, channel,
                                    model, body, requestId));
                });
    }

    /** 控制层 route resolve: 模型不同价不同, 先定价再预扣; 10400 语义透传 (同 LLM 面). */
    private Mono<DistributeVO> resolveRoute(TokenValidateVO token, String model) {
        return channelApi.distribute(DistributeRequest.builder()
                        .tenantId(token.getTenantId())
                        .userId(token.getUserId())
                        .apiKeyId(token.getTokenId())
                        .groupId(token.getGroupId())
                        .model(model).build())
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
                                                     Map<String, Object> body, String requestId) {
        String ownerType = channel.getOwnerType() != null
                ? channel.getOwnerType().name() : "PLATFORM";
        return billingSaga.preConsumeFull(token, channel.getChannelId(), ownerType, model, requestId)
                .flatMap(preConsumeId -> {
                    String taskNo = generateTaskNo();
                    Map<String, Object> payload = buildPayload(model, body, channel);
                    String callbackUrl = props.getTask().getLotask().getWebhookCallbackUrl();
                    return lotaskClient.submit(modality, taskNo, payload, callbackUrl)
                            .flatMap(lotaskId -> mappingStore.put(taskNo, lotaskId,
                                            props.getTask().timeoutOf(modality).plus(Duration.ofHours(24)))
                                    .thenReturn(createdView(modality, taskNo)))
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
     * 轮询 (终态幂等; lotask 不可达 → 502, 调用方退避重试, 状态不变不触计费).
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
                            .flatMap(lotaskClient::get)
                            .map(view -> pollView(taskNo, view));
                });
    }

    /** 轮询视图: 终态返回存储结果; resources 转 sig 代理 URL 为 M2.5c 增量 (当前透传原文). */
    private Map<String, Object> pollView(String taskNo, LotaskTaskView view) {
        TaskStatus status = TaskStateMapper.map(view.status());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_no", taskNo);
        out.put("status", status.name());
        if (status == TaskStatus.SUCCEEDED && view.result() != null) {
            // TODO(M2.5c): resources 转 sig 代理 URL (资源代理 + 缓存索引), 上游 URL 永不透传
            out.put("result", view.result());
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
