package fun.commons.tokengateway.task.schedule;

import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.notify.TerminalEventHandler;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 预扣-终态对账兜底 (《05》§7: webhook outbox 重投穷尽后由本任务补偿,
 * 原 MaintenanceScheduler 孤儿预扣释放语义的简化版——只剩对账, 状态机不兜底).
 *
 * <p>每 10 分钟扫描预扣未闭环清单 (Redis SET): 反查 lotask 终态 →
 * 终态则重放终态处理 (退款/资源转换幂等); 非终态跳过 (超时钟另有职责).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileJob {

    private final TaskMetaStore metaStore;
    private final LotaskTaskClient lotaskClient;
    private final TerminalEventHandler terminalEventHandler;

    @Scheduled(fixedDelayString = "10m", initialDelayString = "5m")
    public void reconcile() {
        metaStore.pendingPreConsumes()
                .flatMap(taskNo -> metaStore.getMeta(taskNo)
                        .flatMap(meta -> lotaskClient.get(meta.lotaskId())
                                .flatMap(view -> {
                                    if (TaskStateMapper.map(view.status()).isTerminal()) {
                                        log.info("[Reconcile] 补偿终态处理: taskNo={}, status={}",
                                                taskNo, view.status());
                                        return terminalEventHandler.onTerminalView(taskNo, meta, view);
                                    }
                                    return Mono.empty();
                                })
                                .onErrorResume(e -> {
                                    log.warn("[Reconcile] 反查失败, 下轮重试: taskNo={}, err={}",
                                            taskNo, e.getMessage());
                                    return Mono.empty();
                                })))
                .then()
                .subscribe(null, e -> log.error("[Reconcile] 对账异常: err={}", e.getMessage()));
    }
}
