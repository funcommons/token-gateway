package fun.commons.tokengateway.worker.lotask;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.lotask.LotaskAuthSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * lotask4j worker 域 API (《06》§3.2 实测端点; jwt + 写操作 HMAC 四头复用 LotaskAuthSigner).
 *
 * <p>poll 无任务 → data=null → empty (不视为错误). 状态查询只取 status (取消信号检测).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerLotaskClient {

    private static final String POLL_PATH = "/api/v1/worker/tasks/poll";

    private final WebClient.Builder webClientBuilder;
    private final TokenGatewayProperties props;
    private final LotaskAuthSigner authSigner;

    private String base() {
        return props.getTask().getLotask().getUrl();
    }

    /** 抢占一个任务; 无任务 → Mono.empty(). */
    public Mono<ClaimedTask> poll(String taskType, String workerId) {
        Map<String, Object> body = Map.of("taskType", taskType, "workerId", workerId);
        byte[] raw = JSON.toJSONBytes(body);
        WebClient.RequestBodySpec spec = webClientBuilder.build().post()
                .uri(base() + POLL_PATH).contentType(MediaType.APPLICATION_JSON);
        authSigner.attachAuth(spec);
        authSigner.attachSignature(spec, "POST", POLL_PATH, raw);
        return spec.bodyValue(new String(raw, java.nio.charset.StandardCharsets.UTF_8))
                .retrieve().bodyToMono(String.class)
                .timeout(props.getTask().getLotask().getReadTimeout())
                .<ClaimedTask>handle((json, sink) -> {
                    JSONObject env = JSON.parseObject(json);
                    if (env.getIntValue("code") != 0 || env.getJSONObject("data") == null) {
                        sink.complete();
                        return;
                    }
                    JSONObject d = env.getJSONObject("data");
                    if (d.get("id") == null) {
                        sink.complete();
                        return;
                    }
                    sink.next(new ClaimedTask(
                            d.getString("id"), d.getString("type"),
                            d.getJSONObject("payload"),
                            d.getLong("executionToken"), d.getInteger("version"),
                            d.getInteger("attempt"),
                            d.getObject("leaseExpireAt", OffsetDateTime.class)));
                })
                .onErrorResume(e -> {
                    log.warn("[WorkerClient] poll 失败 (下轮重试): type={}, err={}",
                            taskType, e.getMessage());
                    return Mono.empty();
                });
    }

    /** 上报进度 (同时续约 lease; fencing 回传). */
    public Mono<Void> progress(ClaimedTask task, String stepKey, int stepProgress) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentStepKey", stepKey);
        body.put("stepProgress", stepProgress);
        body.put("executionToken", task.executionToken());
        body.put("version", task.version());
        return post("/api/v1/worker/tasks/" + task.id() + "/progress", body)
                .onErrorResume(e -> {
                    log.warn("[WorkerClient] progress 失败: id={}, err={}", task.id(), e.getMessage());
                    return Mono.empty();
                });
    }

    /** 上报终态 (SUCCESS/FAILED/CANCELLED; 触发平台状态机 CAS + outbox webhook). */
    public Mono<Void> result(ClaimedTask task, String status, Map<String, Object> result,
                             String errorCode, String errorMsg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (result != null) {
            body.put("result", result);
        }
        if (errorMsg != null) {
            body.put("errorMsg", errorMsg);
            body.put("lastErrorCode", errorCode);
            body.put("lastErrorMessage", errorMsg);
        }
        body.put("executionToken", task.executionToken());
        body.put("version", task.version());
        return post("/api/v1/worker/tasks/" + task.id() + "/result", body)
                .doOnError(e -> log.error("[WorkerClient] result 上报失败: id={}, status={}, err={}",
                        task.id(), status, e.getMessage()));
    }

    /** 查询状态 (取消信号检测: CANCELLING → Worker 停循环上报 CANCELLED). */
    public Mono<String> status(ClaimedTask task) {
        WebClient.RequestHeadersSpec<?> spec = webClientBuilder.build().get()
                .uri(base() + "/api/v1/worker/tasks/" + task.id() + "/status");
        authSigner.attachAuth(spec);
        return spec.retrieve().bodyToMono(String.class)
                .timeout(props.getTask().getLotask().getReadTimeout())
                .map(json -> {
                    JSONObject env = JSON.parseObject(json);
                    JSONObject d = env.getJSONObject("data");
                    return d == null ? null : d.getString("status");
                })
                .onErrorResume(e -> Mono.empty());
    }

    private Mono<Void> post(String path, Map<String, Object> body) {
        byte[] raw = JSON.toJSONBytes(body);
        WebClient.RequestBodySpec spec = webClientBuilder.build().post()
                .uri(base() + path).contentType(MediaType.APPLICATION_JSON);
        authSigner.attachAuth(spec);
        authSigner.attachSignature(spec, "POST", path, raw);
        return spec.bodyValue(new String(raw, java.nio.charset.StandardCharsets.UTF_8))
                .retrieve().bodyToMono(String.class)
                .timeout(props.getTask().getLotask().getReadTimeout())
                .flatMap(json -> {
                    JSONObject env = JSON.parseObject(json);
                    if (env.getIntValue("code") != 0) {
                        return Mono.error(new IllegalStateException(
                                "lotask worker 域拒绝: " + env.getString("message")));
                    }
                    return Mono.empty();
                })
                .then();
    }
}
