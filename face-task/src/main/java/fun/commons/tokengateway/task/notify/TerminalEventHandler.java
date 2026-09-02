package fun.commons.tokengateway.task.notify;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.spi.model.TaskStatus;
import fun.commons.tokengateway.task.billing.TaskBillingSaga;
import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.lotask.LotaskTaskView;
import fun.commons.tokengateway.task.resource.ResourceSigner;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskMetaStore.TaskMeta;
import fun.commons.tokengateway.task.state.TaskStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 终态事件处理 (《05》§5.2: webhook 驱动退款/消费 + notify; 三源触发同一入口——
 * webhook 验签通过 / verify-then-act 回查 / 超时钟与对账兜底, 全靠退款幂等收口).
 *
 * <p>SUCCESS → result.resources 转 sig 代理 URL 落终态存储 (上游 URL 永不透传), 预扣转消费;
 * FAILED/CANCELLED → 全额退款 (幂等) → notify. 顺序: 先退款成功再 notify (《05》§7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminalEventHandler {

    private final TaskMetaStore metaStore;
    private final TaskBillingSaga billingSaga;
    private final NotifyDispatcher notifyDispatcher;
    private final fun.commons.tokengateway.task.ResourceUrlConverter resourceUrlConverter;
    private final TokenGatewayProperties props;

    /**
     * 处理终态事件 (非终态/未知任务静默忽略——轮询中的进度事件不收).
     *
     * @param taskNo  网关任务号 (反查自 lotask id)
     * @param meta    任务元数据 (缺失时仅告警: 无法退款, 等对账兜底)
     * @param status  平台状态原文 (SUCCESS/FAILED/CANCELLED/...)
     * @param result  平台终态结果 (可空)
     */
    public Mono<Void> onTerminal(String taskNo, TaskMeta meta, String status,
                                 Map<String, Object> result) {
        TaskStatus mapped = TaskStateMapper.map(status);
        if (!mapped.isTerminal()) {
            return Mono.empty();
        }
        if (meta == null) {
            log.error("[Terminal] 元数据缺失, 等待对账兜底: taskNo={}, status={}", taskNo, status);
            return Mono.empty();
        }
        Duration ttl = props.getTask().timeoutOf(meta.modality()).plus(java.time.Duration.ofHours(24));
        Mono<Void> settle;
        Map<String, Object> notifyBody;
        if (mapped == TaskStatus.SUCCEEDED) {
            Map<String, Object> converted = resourceUrlConverter.convert(taskNo, result);
            // SUCCEEDED 也须计费闭环: 预扣转实扣 (settle), 与 saveTerminalResult 串行
            settle = billingSaga.settleOnce(meta.preConsumeId(), taskNo)
                    .then(metaStore.saveTerminalResult(taskNo,
                            JSON.toJSONString(terminalEntry(TaskStatus.SUCCEEDED, converted, null)), ttl));
            notifyBody = notifyBody(taskNo, TaskStatus.SUCCEEDED, converted, null);
        } else {
            settle = billingSaga.refundOnce(meta.preConsumeId(), "task " + mapped.name(), taskNo)
                    .then(metaStore.saveTerminalResult(taskNo,
                            JSON.toJSONString(terminalEntry(mapped, null, result)), ttl));
            notifyBody = notifyBody(taskNo, mapped, null, result);
        }
        return settle
                .then(metaStore.clearDeadline(taskNo))
                .then(metaStore.closePending(taskNo))
                .then(Mono.fromRunnable(() -> notifyDispatcher.dispatch(
                        taskNo, meta.notifyUrl(), notifyBody)));
    }

    /**
     * 超时钟判定 EXPIRED (R6 网关侧补偿): 全额退款 + 落 EXPIRED 终态条目 (poll 优先读它,
     * 调用方看到 EXPIRED 而非平台原文) + notify.
     */
    public Mono<Void> onExpired(String taskNo, TaskMeta meta) {
        Duration ttl = props.getTask().timeoutOf(meta.modality()).plus(java.time.Duration.ofHours(24));
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "TIMEOUT");
        error.put("message", "任务超时 (" + props.getTask().timeoutOf(meta.modality()) + " 未达终态)");
        return billingSaga.refundOnce(meta.preConsumeId(), "task EXPIRED", taskNo)
                .then(metaStore.saveTerminalResult(taskNo,
                        JSON.toJSONString(terminalEntry(TaskStatus.EXPIRED, null, error)), ttl))
                .then(metaStore.clearDeadline(taskNo))
                .then(metaStore.closePending(taskNo))
                .then(Mono.fromRunnable(() -> notifyDispatcher.dispatch(
                        taskNo, meta.notifyUrl(), notifyBody(taskNo, TaskStatus.EXPIRED, null, error))));
    }

    /** 终态条目 (poll 终态幂等读它, 不触 lotask): {status, result?|error?}. */
    private static Map<String, Object> terminalEntry(TaskStatus status, Map<String, Object> result,
                                                     Map<String, Object> error) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("status", status.name());
        if (result != null) {
            entry.put("result", result);
        }
        if (error != null) {
            entry.put("error", error);
        }
        return entry;
    }


    private static Map<String, Object> notifyBody(String taskNo, TaskStatus status,
                                                  Map<String, Object> result,
                                                  Map<String, Object> rawResult) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_no", taskNo);
        body.put("status", status.name());
        if (status == TaskStatus.SUCCEEDED && result != null) {
            body.put("result", result);
        }
        if (status != TaskStatus.SUCCEEDED && rawResult != null) {
            body.put("error", rawResult);
        }
        return body;
    }

    /** 由 lotask 任务视图构造终态处理入参 (verify-then-act 回查/超时钟/对账共用). */
    public Mono<Void> onTerminalView(String taskNo, TaskMeta meta, LotaskTaskView view) {
        return onTerminal(taskNo, meta, view.status(), view.result());
    }
}
