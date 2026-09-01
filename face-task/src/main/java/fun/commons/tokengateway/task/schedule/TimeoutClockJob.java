package fun.commons.tokengateway.task.schedule;

import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.notify.TerminalEventHandler;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 超时钟 (《05》§10 R6 网关侧补偿: lotask4j 超时混在 FAILED 无独立 error_code,
 * EXPIRED 由网关判定).
 *
 * <p>每分钟扫描 deadline 已到的在途任务 (Redis ZSET): 反查 lotask 终态——
 * 已终态 → 走正常终态处理 (webhook 丢失补偿); 仍非终态 → 映射 EXPIRED + 全额退款 + notify.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutClockJob {

    private final TaskMetaStore metaStore;
    private final LotaskTaskClient lotaskClient;
    private final TerminalEventHandler terminalEventHandler;

    @Scheduled(fixedDelayString = "60s", initialDelayString = "30s")
    public void scan() {
        long now = System.currentTimeMillis();
        metaStore.dueDeadlines(now)
                .flatMap(taskNo -> metaStore.getMeta(taskNo)
                        .flatMap(meta -> lotaskClient.get(meta.lotaskId())
                                .flatMap(view -> {
                                    if (fun.commons.tokengateway.task.state.TaskStateMapper
                                            .map(view.status()).isTerminal()) {
                                        // 平台已终态 (webhook 丢失窗口补偿)
                                        return terminalEventHandler.onTerminalView(taskNo, meta, view);
                                    }
                                    log.warn("[TimeoutClock] 超时时钟判定 EXPIRED: taskNo={}, "
                                            + "lotaskStatus={}", taskNo, view.status());
                                    return terminalEventHandler.onExpired(taskNo, meta);
                                })
                                .onErrorResume(e -> {
                                    // 反查失败不移除 deadline, 下轮重试
                                    log.warn("[TimeoutClock] 反查失败, 下轮重试: taskNo={}, err={}",
                                            taskNo, e.getMessage());
                                    return Mono.empty();
                                })))
                .then()
                .subscribe(null, e -> log.error("[TimeoutClock] 扫描异常: err={}", e.getMessage()));
    }
}
