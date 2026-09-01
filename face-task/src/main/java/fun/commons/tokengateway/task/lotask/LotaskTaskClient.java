package fun.commons.tokengateway.task.lotask;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.spi.config.LotaskFaceConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * lotask4j client 域 API 封装 (《06》§3.1: submit/get/cancel).
 *
 * <p>平台 V4+ 实测端点: POST /api/v1/client/tasks/submit (POST-only 子路径,
 * 签名圈定写端点), GET /{id} (OpenID), POST /{id}/cancel.
 * 信封 = framework4j ApiResponse {code, message, data}; code==0 为成功.
 *
 * <p>错误映射: 平台 4xx/业务失败 → 502 + 10004 (上游故障语义, 与 LLM 面 distribute 失败同口径);
 * 任务不存在 (查询) → 404 + 10400. RPC 异常统一 onErrorResume → 502, 不向上抛基础设施异常.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotaskTaskClient {

    private static final String SUBMIT_PATH = "/api/v1/client/tasks/submit";

    private final WebClient.Builder webClientBuilder;
    private final TokenGatewayProperties props;
    private final LotaskAuthSigner authSigner;

    private LotaskFaceConfig cfg() {
        return props.getTask().getLotask();
    }

    /**
     * 提交任务, 返回 lotask 任务 ID (OpenID 字符串).
     *
     * @param taskType       任务类型 (video/image/audio/tts, 决定 Worker 脚本与超时档)
     * @param idempotencyKey 外部幂等键 (= 网关 task_no; 平台租户分区内唯一)
     * @param payload        任务载荷 (params + notify_url + 加密路由快照)
     * @param callbackUrl    终态 webhook 地址 (网关 /internal/lotask/webhook)
     */
    public Mono<String> submit(String taskType, String idempotencyKey,
                               Map<String, Object> payload, String callbackUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", taskType);
        body.put("payload", payload);
        body.put("idempotencyKey", idempotencyKey);
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            body.put("callbackUrl", callbackUrl);
        }
        byte[] raw = JSON.toJSONBytes(body);
        WebClient.RequestBodySpec spec = webClientBuilder.build().post()
                .uri(cfg().getUrl() + SUBMIT_PATH)
                .contentType(MediaType.APPLICATION_JSON);
        authSigner.attachAuth(spec);
        authSigner.attachSignature(spec, "POST", SUBMIT_PATH, raw);
        return spec.bodyValue(new String(raw, StandardCharsets.UTF_8))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(cfg().getReadTimeout())
                .map(json -> {
                    JSONObject env = JSON.parseObject(json);
                    if (env.getIntValue("code") != 0) {
                        throw new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                                "lotask submit 失败: " + env.getString("message"));
                    }
                    JSONObject data = env.getJSONObject("data");
                    String id = data == null ? null : data.getString("id");
                    if (id == null || id.isBlank()) {
                        throw new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                                "lotask submit 响应缺任务 id");
                    }
                    return id;
                })
                .onErrorResume(e -> e instanceof RelayException re
                        ? Mono.error(re)
                        : Mono.error(new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                                "lotask submit RPC 失败: " + e.getMessage())));
    }

    /**
     * 查询任务详情 (poll 唯一通道; 终态幂等).
     */
    public Mono<LotaskTaskView> get(String lotaskId) {
        String path = "/api/v1/client/tasks/" + lotaskId;
        WebClient.RequestHeadersSpec<?> spec = webClientBuilder.build().get()
                .uri(cfg().getUrl() + path);
        authSigner.attachAuth(spec);
        return spec.retrieve()
                .bodyToMono(String.class)
                .timeout(cfg().getReadTimeout())
                .map(json -> {
                    JSONObject env = JSON.parseObject(json);
                    if (env.getIntValue("code") != 0) {
                        // 任务不存在 → 404 语义透传 (调用方 poll 侧映射 10400)
                        if (env.getIntValue("code") == ApiCode.NOT_FOUND.getCode()) {
                            throw new RelayException(404, ApiCode.NOT_FOUND.getCode(),
                                    "任务不存在: " + lotaskId);
                        }
                        throw new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                                "lotask get 失败: " + env.getString("message"));
                    }
                    JSONObject d = env.getJSONObject("data");
                    if (d == null) {
                        throw new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                                "lotask get 响应缺 data");
                    }
                    return new LotaskTaskView(
                            d.getString("id"),
                            d.getString("status"),
                            d.getJSONObject("result"),
                            d.getString("errorCode"),
                            d.getString("errorMessage"));
                })
                .onErrorResume(e -> e instanceof RelayException re
                        ? Mono.error(re)
                        : Mono.error(new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                                "lotask get RPC 失败: " + e.getMessage())));
    }

    /**
     * 取消任务 (发取消信号, Worker 循环检测; M2.5c 运营/调用方取消入口使用).
     */
    public Mono<Void> cancel(String lotaskId) {
        String path = "/api/v1/client/tasks/" + lotaskId + "/cancel";
        WebClient.RequestBodySpec spec = webClientBuilder.build().post()
                .uri(cfg().getUrl() + path)
                .contentType(MediaType.APPLICATION_JSON);
        authSigner.attachAuth(spec);
        authSigner.attachSignature(spec, "POST", path, new byte[0]);
        return spec.bodyValue("{}")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(cfg().getReadTimeout())
                .doOnError(e -> log.error("[Lotask] cancel RPC 失败: id={}, err={}",
                        lotaskId, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }
}
