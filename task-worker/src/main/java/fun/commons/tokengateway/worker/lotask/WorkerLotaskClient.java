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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 类型拒绝告警节流 (每 type 5 分钟 1 条; 注册未就绪窗口的可见性, 不刷屏). */
    private static final long TYPE_REJECT_LOG_INTERVAL_MS = 300_000;

    private final WebClient.Builder webClientBuilder;
    private final TokenGatewayProperties props;
    private final LotaskAuthSigner authSigner;

    /** type → 最近一次类型拒绝告警时刻 (节流). */
    private final Map<String, Long> lastTypeRejectLogAt = new ConcurrentHashMap<>();

    /** type → 累计类型拒绝次数 (20101 未注册 / 20102 禁用; 只读暴露). */
    private final Map<String, Long> typeRejections = new ConcurrentHashMap<>();

    private String base() {
        return props.getTask().getLotask().getUrl();
    }

    private boolean isAuthKick(Throwable e) {
        return e instanceof WebClientResponseException w && w.getStatusCode().value() == 401;
    }

    /**
     * 类型拒绝记账 + 节流告警 (20101 未注册 / 20102 禁用; 零配置轮次: 自动注册
     * 自愈前的可见性窗口). 语义保持 Mono.empty (poll 空转下轮重试, 不中断循环).
     */
    private void onTypeRejected(String taskType, int code, String message) {
        typeRejections.merge(taskType, 1L, Long::sum);
        long now = System.currentTimeMillis();
        Long last = lastTypeRejectLogAt.get(taskType);
        if (last == null || now - last >= TYPE_REJECT_LOG_INTERVAL_MS) {
            lastTypeRejectLogAt.put(taskType, now);
            log.warn("[WorkerClient] 类型拒绝 (注册未就绪?): type={}, code={}, msg={}",
                    taskType, code, message);
        }
    }

    /** 只读快照: type → 累计类型拒绝次数 (运维观测/测试断言用). */
    public Map<String, Long> unknownTypeRejections() {
        return Map.copyOf(typeRejections);
    }

    /** 抢占一个任务; 无任务 → Mono.empty(). */
    public Mono<ClaimedTask> poll(String taskType, String workerId) {
        Map<String, Object> body = Map.of("taskType", taskType, "workerId", workerId);
        byte[] raw = JSON.toJSONBytes(body);
        return authSigner.authorize(webClientBuilder.build().post()
                        .uri(base() + POLL_PATH).contentType(MediaType.APPLICATION_JSON))
                .flatMap(spec -> {
                    authSigner.attachSignature(spec, "POST", POLL_PATH, raw);
                    return spec.bodyValue(new String(raw, java.nio.charset.StandardCharsets.UTF_8))
                            .retrieve().bodyToMono(String.class)
                            .timeout(props.getTask().getLotask().getReadTimeout());
                })
                .<ClaimedTask>handle((json, sink) -> {
                    JSONObject env = JSON.parseObject(json);
                    int code = env.getIntValue("code");
                    if (code != 0 || env.getJSONObject("data") == null) {
                        if (code == 20101 || code == 20102) {
                            onTypeRejected(taskType, code, env.getString("message"));
                        }
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
                    if (isAuthKick(e)) {
                        log.warn("[WorkerClient] 令牌被撤销 (401), 清共享缓存, 下轮重登录: type={}",
                                taskType);
                        return authSigner.invalidate().then(Mono.empty());
                    }
                    log.warn("[WorkerClient] poll 失败 (下轮重试): type={}, err={}",
                            taskType, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 上报进度 (同时续约 lease; fencing 回传).
     * 平台 progressWithVersion 成功即 version+1 且不回传 —— CAS 成功后本地 bumpVersion 同步,
     * 否则下一次 progress 起全部 fencing 失败.
     */
    public Mono<Void> progress(ClaimedTask task, String stepKey, int stepProgress) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentStepKey", stepKey);
        body.put("stepProgress", stepProgress);
        body.put("executionToken", task.executionToken());
        body.put("version", task.version());
        return post("/api/v1/worker/tasks/" + task.id() + "/progress", body, task::bumpVersion)
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
        return post("/api/v1/worker/tasks/" + task.id() + "/result", body, null)
                .doOnError(e -> log.error("[WorkerClient] result 上报失败: id={}, status={}, err={}",
                        task.id(), status, e.getMessage()));
    }

    /** 查询状态 (取消信号检测: CANCELLING → Worker 停循环上报 CANCELLED). */
    public Mono<String> status(ClaimedTask task) {
        return authSigner.authorize(webClientBuilder.build().get()
                        .uri(base() + "/api/v1/worker/tasks/" + task.id() + "/status"))
                .flatMap(spec -> spec.retrieve().bodyToMono(String.class)
                        .timeout(props.getTask().getLotask().getReadTimeout()))
                .map(json -> {
                    JSONObject env = JSON.parseObject(json);
                    JSONObject d = env.getJSONObject("data");
                    return d == null ? null : d.getString("status");
                })
                .onErrorResume(e -> isAuthKick(e)
                        ? authSigner.invalidate().then(Mono.empty())
                        : Mono.empty());
    }

    private Mono<Void> post(String path, Map<String, Object> body, Runnable onSuccess) {
        byte[] raw = JSON.toJSONBytes(body);
        return authSigner.authorize(webClientBuilder.build().post()
                        .uri(base() + path).contentType(MediaType.APPLICATION_JSON))
                .flatMap(spec -> {
                    authSigner.attachSignature(spec, "POST", path, raw);
                    return spec.bodyValue(new String(raw, java.nio.charset.StandardCharsets.UTF_8))
                            .retrieve().bodyToMono(String.class)
                            .timeout(props.getTask().getLotask().getReadTimeout());
                })
                .flatMap(json -> {
                    JSONObject env = JSON.parseObject(json);
                    if (env.getIntValue("code") != 0) {
                        return Mono.error(new IllegalStateException(
                                "lotask worker 域拒绝: " + env.getString("message")));
                    }
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    return Mono.empty();
                })
                .onErrorResume(e -> isAuthKick(e)
                        ? authSigner.invalidate().then(Mono.error(e))
                        : Mono.error(e))
                .then();
    }
}
