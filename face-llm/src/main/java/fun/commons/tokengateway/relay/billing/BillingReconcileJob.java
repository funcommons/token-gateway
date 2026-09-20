package fun.commons.tokengateway.relay.billing;

import fun.commons.tokengateway.contract.RefundRequest;
import fun.commons.tokengateway.contract.SettleVO;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * settle/refund 待重放扫描 job (issue #23, 参照任务面 ReconcileJob 的 @Scheduled 风格).
 *
 * <p>每 interval 扫描 {@code tgw:billing:pending} 到期条目 → 按原参数重放 settle/refund
 * (调 HttpBillingApi 既有方法, 幂等锚 preConsumeId) → 三态:
 * <ul>
 *   <li>确认成功 → zrem 出队, INFO {@code [Billing-Reconcile]}</li>
 *   <li>基础设施失败且未耗尽 → retries+1 指数退避重排, WARN {@code [Billing-Reconcile]}</li>
 *   <li>基础设施失败且耗尽 / 业务拒绝 (code!=0 重试无意义) → 出队 + ERROR
 *       {@code [Billing-DeadLetter]} 结构化快照</li>
 * </ul>
 *
 * <p>enabled=false 时不装配 (gateway.billing-reconcile.enabled); enqueue 侧不受影响.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "gateway.billing-reconcile", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BillingReconcileJob {

    /** 单轮扫描上限 (参照任务面 pending/扫描的 take(100) 量级, LLM 面失败率低取 50). */
    private static final int BATCH_LIMIT = 50;

    private final BillingPendingStore pendingStore;
    private final HttpBillingApi billingApi;
    private final BillingReconcileProperties properties;

    @Scheduled(fixedDelayString = "${gateway.billing-reconcile.interval:30s}",
            initialDelayString = "${gateway.billing-reconcile.interval:30s}")
    public void reconcile() {
        pendingStore.duePending(BATCH_LIMIT)
                .concatMap(this::replay)
                .then()
                .subscribe(null, e -> log.error("[Billing-Reconcile] 扫描异常: err={}", e.toString()));
    }

    /** 单条重放: 按记录型分发到 settle/refund 既有 RPC; 异常不中断整轮扫描. */
    private Mono<Void> replay(BillingPendingStore.BillingPendingEntry entry) {
        BillingPendingRecord record = entry.record();
        Mono<Void> done = record.isRefund()
                ? billingApi.refund(RefundRequest.builder()
                        .preConsumeId(record.preConsumeId())
                        .reason(record.refundReason())
                        .requestId(record.requestId())
                        .build())
                .flatMap(resp -> onReplayed(entry, resp))
                : billingApi.settle(record.toSettleRequest())
                .flatMap(resp -> onReplayed(entry, resp));
        return done.onErrorResume(e -> {
            // 重放调用自身异常 (理论不可达, HttpBillingApi 折叠异常): 保留 pending 下轮重试
            log.warn("[Billing-Reconcile] 重放异常, 保留 pending 下轮重试: type={}, preConsumeId={}, "
                    + "retries={}, err={}", record.type(), record.preConsumeId(), record.retries(),
                    e.toString());
            return Mono.empty();
        });
    }

    /** 重放结果三态归口 (确认成功 / 退避重排 / 死信). */
    private Mono<Void> onReplayed(BillingPendingStore.BillingPendingEntry entry,
                                  ApiResponse<?> resp) {
        BillingPendingRecord record = entry.record();
        if (confirmed(record, resp)) {
            log.info("[Billing-Reconcile] 重放成功: type={}, preConsumeId={}, retries={}, credit={}",
                    record.type(), record.preConsumeId(), record.retries(),
                    resp.getData() instanceof SettleVO settle ? settle.getCreditConsumed() : null);
            return pendingStore.remove(entry).then();
        }
        if (BillingResponsePolicy.businessRejected(resp)) {
            // 业务拒绝 (信封 code!=0 且非基础设施折叠码): 同参数重试无意义, 直接死信
            return deadLetter(entry, "reconcile 业务拒绝: code="
                    + (resp == null ? -1 : resp.getCode())
                    + ", message=" + (resp == null ? "no response" : resp.getMessage()));
        }
        int nextRetries = record.retries() + 1;
        if (nextRetries >= properties.getMaxAttempts()) {
            return deadLetter(entry, "reconcile 重放次数耗尽: retries=" + nextRetries
                    + ", message=" + (resp == null ? "no response" : resp.getMessage()));
        }
        long nextRetryAt = System.currentTimeMillis() + properties.backoffMs(nextRetries);
        log.warn("[Billing-Reconcile] 重放失败已重排: type={}, preConsumeId={}, retries={}, "
                + "nextRetryAt={}, err={}", record.type(), record.preConsumeId(), nextRetries,
                nextRetryAt, resp == null ? "no response" : resp.getMessage());
        return pendingStore.reschedule(entry, record.withRetries(nextRetries), nextRetryAt).then();
    }

    /** 死信出队: 结构化快照 (含全部结算参数) + zrem, 人工捞取后可按 preConsumeId 重放. */
    private Mono<Void> deadLetter(BillingPendingStore.BillingPendingEntry entry, String reason) {
        BillingDeadLetters.log(entry.record(), "reconcile", reason);
        return pendingStore.remove(entry).then();
    }

    /**
     * 重放确认: settle 须 code=0 且 creditConsumed 非空 (载荷不完整视同未确认,
     * 走可重试分支); refund 无载荷, code=0 即确认.
     */
    private static boolean confirmed(BillingPendingRecord record, ApiResponse<?> resp) {
        if (resp == null || !resp.isSuccess()) {
            return false;
        }
        return record.isRefund() || resp.getData() instanceof SettleVO settle
                && settle.getCreditConsumed() != null;
    }
}
