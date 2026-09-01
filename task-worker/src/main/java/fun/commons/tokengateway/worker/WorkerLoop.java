package fun.commons.tokengateway.worker;

import com.alibaba.fastjson2.JSON;
import fun.commons.tokengateway.task.lotask.RouteSnapshotCipher;
import fun.commons.tokengateway.worker.config.WorkerProperties;
import fun.commons.tokengateway.worker.lotask.ClaimedTask;
import fun.commons.tokengateway.worker.lotask.WorkerLotaskClient;
import fun.commons.tokengateway.worker.script.ScriptExecutor;
import fun.commons.tokengateway.worker.script.GroovySandbox;
import fun.commons.tokengateway.worker.script.ScriptHttpClient;
import fun.commons.tokengateway.worker.script.ScriptLoader;
import fun.commons.tokengateway.worker.script.ScriptLoader.ScriptAsset;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Worker 执行循环 (《05》§5.2: poll → Groovy 三钩子 → result 上报).
 *
 * <p>节拍: 按 ScriptLoader 当前 taskType 集拉单 (有脚本才有消费资格);
 * 任务内: create → 上报 upstream 任务 ID → 循环 poll 钩子 (间隔 worker.upstream-poll-interval,
 * 每 N 次检查取消信号, progress 上报兼续 lease) → 终态 resultMapping → reportResult.
 * 脚本异常/超时 → FAILED + SCRIPT_ERROR (《05》§9.5); 超 maxTaskDuration → FAILED + TIMEOUT
 * (网关超时钟另有兜底, 双保险).
 */
@Slf4j
@Component
public class WorkerLoop {

    private final WorkerLotaskClient lotaskClient;
    private final ScriptLoader scriptLoader;
    private final ScriptExecutor scriptExecutor;
    private final ScriptHttpClient http;
    private final RouteSnapshotCipher snapshotCipher;
    private final WorkerProperties props;
    private final String workerId;
    private final Semaphore concurrency;
    private final ExecutorService taskPool;

    public WorkerLoop(WorkerLotaskClient lotaskClient, ScriptLoader scriptLoader,
                      GroovySandbox sandbox, ScriptHttpClient http,
                      RouteSnapshotCipher snapshotCipher, WorkerProperties props) {
        this.lotaskClient = lotaskClient;
        this.scriptLoader = scriptLoader;
        this.scriptExecutor = new ScriptExecutor(sandbox, props.getHookTimeout());
        this.http = http;
        this.snapshotCipher = snapshotCipher;
        this.props = props;
        this.workerId = props.getId() != null ? props.getId()
                : "wkr-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        this.concurrency = new Semaphore(props.getConcurrency());
        this.taskPool = Executors.newCachedThreadPool();
    }

    @PostConstruct
    void init() {
        scriptLoader.reload();
        log.info("[WorkerLoop] worker={} 启动, taskTypes={}, concurrency={}",
                workerId, scriptLoader.taskTypes(), props.getConcurrency());
    }

    @Scheduled(fixedDelayString = "${worker.poll-interval:5s}",
            initialDelayString = "${worker.poll-interval:5s}")
    public void tick() {
        scriptLoader.reload();
        for (String taskType : scriptLoader.taskTypes()) {
            if (!concurrency.tryAcquire()) {
                return;
            }
            lotaskClient.poll(taskType, workerId)
                    .subscribe(
                            task -> Mono.fromRunnable(() -> runTask(task))
                                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                                    .subscribe(),
                            e -> {
                                concurrency.release();
                                log.warn("[WorkerLoop] poll 异常: type={}, err={}",
                                        taskType, e.getMessage());
                            },
                            () -> concurrency.release()); // 无任务 → 释放令牌
        }
    }

    /** 单任务执行 (虚拟线程; 脚本钩子经 ScriptExecutor 超时收口). */
    void runTask(ClaimedTask task) {
        try {
            ScriptAsset script = scriptLoader.forType(task.type())
                    .orElseThrow(() -> new IllegalStateException("无脚本: " + task.type()));
            Map<String, Object> ctx = buildCtx(task);

            // ① create 钩子 → 上游任务 ID
            Map<String, Object> created = scriptExecutor.invoke(
                    script.cacheKey(), script.source(), "create", ctx, http);
            Object upstreamTaskId = created.get("upstreamTaskId");
            if (upstreamTaskId != null) {
                ctx.put("upstreamTaskId", upstreamTaskId.toString());
            }
            lotaskClient.progress(task, "created", 0).block();

            // ② poll 钩子循环直至终态/取消/超时
            long deadline = System.currentTimeMillis() + props.getMaxTaskDuration().toMillis();
            int sinceStatusCheck = 0;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(props.getUpstreamPollInterval().toMillis());
                if (++sinceStatusCheck >= props.getStatusCheckEvery()) {
                    sinceStatusCheck = 0;
                    String status = lotaskClient.status(task).block();
                    if ("CANCELLING".equals(status)) {
                        lotaskClient.result(task, "CANCELLED", null, null, null).block();
                        log.info("[WorkerLoop] 任务取消: id={}", task.id());
                        return;
                    }
                }
                Map<String, Object> polled = scriptExecutor.invoke(
                        script.cacheKey(), script.source(), "poll", ctx, http);
                String state = String.valueOf(polled.getOrDefault("state", "RUNNING"));
                ctx.put("raw", polled.get("raw"));
                if ("SUCCEEDED".equals(state)) {
                    Map<String, Object> mapped = scriptExecutor.invoke(
                            script.cacheKey(), script.source(), "resultMapping", ctx, http);
                    lotaskClient.result(task, "SUCCESS", mapped, null, null).block();
                    log.info("[WorkerLoop] 任务成功: id={}", task.id());
                    return;
                }
                if ("FAILED".equals(state)) {
                    lotaskClient.result(task, "FAILED", null,
                            "UPSTREAM_FAILED", String.valueOf(polled.getOrDefault("error", "")))
                            .block();
                    log.warn("[WorkerLoop] 任务失败: id={}", task.id());
                    return;
                }
                lotaskClient.progress(task, "polling",
                        ((Number) polled.getOrDefault("progressHint", 0)).intValue()).block();
            }
            lotaskClient.result(task, "FAILED", null, "TIMEOUT",
                    "超过 worker.max-task-duration").block();
        } catch (ScriptExecutor.ScriptHookException e) {
            log.error("[WorkerLoop] 脚本失败: id={}, hook={}, err={}", task.id(), e.hook(),
                    e.getMessage());
            lotaskClient.result(task, "FAILED", null, "SCRIPT_ERROR", e.getMessage())
                    .onErrorResume(err -> Mono.empty()).block();
        } catch (Exception e) {
            log.error("[WorkerLoop] 执行异常: id={}, err={}", task.id(), e.getMessage());
            lotaskClient.result(task, "FAILED", null, "WORKER_ERROR", e.getMessage())
                    .onErrorResume(err -> Mono.empty()).block();
        } finally {
            concurrency.release();
        }
    }

    /** 钩子上下文: payload + 解密路由快照 (Worker 侧持钥, 《05》§8 R7). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCtx(ClaimedTask task) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        Map<String, Object> payload = task.payload() != null ? task.payload() : Map.of();
        ctx.put("payload", payload);
        Object snapshot = payload.get("routeSnapshot");
        if (snapshot != null) {
            ctx.put("routeSnapshot", JSON.parseObject(
                    snapshotCipher.decrypt(snapshot.toString())));
        }
        ctx.put("progress", new LinkedHashMap<String, Object>());
        return ctx;
    }
}
