package fun.commons.tokengateway.task.schedule;

import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.lotask.LotaskTaskView;
import fun.commons.tokengateway.task.notify.TerminalEventHandler;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskMetaStore.TaskMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超时钟 + 对账兜底 job 的调度逻辑单测 (mock 三依赖, 不跑 @Scheduled 本体).
 *
 * <p>TimeoutClockJob: deadline 到 → 平台终态走正常终态处理 (webhook 丢失补偿),
 * 非终态判 EXPIRED; ReconcileJob: 预扣未闭环 → 终态重放, 非终态跳过.
 */
class ScheduleJobsTest {

    private TaskMetaStore metaStore;
    private LotaskTaskClient lotaskClient;
    private TerminalEventHandler handler;
    private TimeoutClockJob timeoutClockJob;
    private ReconcileJob reconcileJob;

    private static final TaskMeta META =
            new TaskMeta("lotask-1", "pc-1", "video", null, 0L, "sk-key");

    @BeforeEach
    void setUp() {
        metaStore = Mockito.mock(TaskMetaStore.class);
        lotaskClient = Mockito.mock(LotaskTaskClient.class);
        handler = Mockito.mock(TerminalEventHandler.class);
        timeoutClockJob = new TimeoutClockJob(metaStore, lotaskClient, handler);
        reconcileJob = new ReconcileJob(metaStore, lotaskClient, handler);
        when(handler.onTerminalView(anyString(), any(), any())).thenReturn(Mono.empty());
        when(handler.onExpired(anyString(), any())).thenReturn(Mono.empty());
    }

    private void view(String status) {
        when(lotaskClient.get("lotask-1")).thenReturn(
                Mono.just(new LotaskTaskView("lotask-1", status, null, null, null)));
    }

    @Test
    void timeoutClock_terminalAtPlatformReplaysTerminalHandler() {
        when(metaStore.dueDeadlines(any(Long.class))).thenReturn(Flux.just("T-1"));
        when(metaStore.getMeta("T-1")).thenReturn(Mono.just(META));
        view("SUCCESS");
        timeoutClockJob.scan();
        verify(handler).onTerminalView("T-1", META,
                new LotaskTaskView("lotask-1", "SUCCESS", null, null, null));
        verify(handler, never()).onExpired(anyString(), any());
    }

    @Test
    void timeoutClock_nonTerminalMarksExpired() {
        when(metaStore.dueDeadlines(any(Long.class))).thenReturn(Flux.just("T-1"));
        when(metaStore.getMeta("T-1")).thenReturn(Mono.just(META));
        view("RUNNING");
        timeoutClockJob.scan();
        verify(handler).onExpired("T-1", META);
        verify(handler, never()).onTerminalView(anyString(), any(), any());
    }

    @Test
    void timeoutClock_missingMetaOrUpstreamErrorSkipsGracefully() {
        when(metaStore.dueDeadlines(any(Long.class))).thenReturn(Flux.just("T-1"));
        when(metaStore.getMeta("T-1")).thenReturn(Mono.empty());
        timeoutClockJob.scan();
        verify(handler, never()).onExpired(anyString(), any());

        when(metaStore.getMeta("T-1")).thenReturn(Mono.just(META));
        when(lotaskClient.get("lotask-1")).thenReturn(Mono.error(new IllegalStateException("down")));
        timeoutClockJob.scan();  // 反查失败: 下轮重试, 不抛
        verify(handler, never()).onExpired(anyString(), any());
    }

    @Test
    void reconcile_terminalReplaysAndNonTerminalSkips() {
        when(metaStore.pendingPreConsumes()).thenReturn(Flux.just("T-1"));
        when(metaStore.getMeta("T-1")).thenReturn(Mono.just(META));
        view("FAILED");
        reconcileJob.reconcile();
        verify(handler).onTerminalView("T-1", META,
                new LotaskTaskView("lotask-1", "FAILED", null, null, null));

        view("PENDING");
        Mockito.clearInvocations(handler);
        reconcileJob.reconcile();
        verify(handler, never()).onTerminalView(anyString(), any(), any());
    }

    @Test
    void reconcile_upstreamErrorSwallowed() {
        when(metaStore.pendingPreConsumes()).thenReturn(Flux.just("T-1"));
        when(metaStore.getMeta("T-1")).thenReturn(Mono.just(META));
        when(lotaskClient.get("lotask-1")).thenReturn(Mono.error(new IllegalStateException("down")));
        reconcileJob.reconcile();
        verify(handler, never()).onTerminalView(anyString(), any(), any());
    }
}
