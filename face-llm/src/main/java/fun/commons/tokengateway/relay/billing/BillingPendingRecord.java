package fun.commons.tokengateway.relay.billing;

import fun.commons.tokengateway.contract.SettleRequest;

import java.util.List;

/**
 * 待重放结算记录 (issue #23): {@link BillingPendingStore} ZSET 的成员,
 * member = 本记录 JSON (fastjson2 单行), score = 下次重试 epoch ms.
 *
 * <p>字段 = settle/refund 的全部结算参数原样快照 + 重试计数 {@code retries};
 * 重放幂等锚 = {@code preConsumeId} (能力面按 pre_consume_id/request_id 幂等,
 * 重放多发一次 settle/refund 无副作用).
 *
 * <p>两型记录共用一结构: settle 型携带 token 细分与 attempts 明细, refund 型仅
 * {@code refundReason}; 无关字段为 null, fastjson2 序列化时省略 (member 精简).
 */
public record BillingPendingRecord(
        /** 记录型: settle | refund (常量 {@link #TYPE_SETTLE} / {@link #TYPE_REFUND}). */
        String type,
        /** 幂等锚: 预扣单 id, 重放同值 = 能力面幂等去重. */
        String preConsumeId,
        /** 网关 trace/request id (与原请求一致, 不换不重造). */
        String requestId,
        /** 结算主体 (③⑦ 对账 owner 桥, 原样重放). */
        Long ownerPartyId,
        Integer actualPromptTokens,
        Integer actualCompletionTokens,
        Integer cacheReadTokens,
        Integer cacheCreationTokens,
        Long reasoningTokens,
        Long audioTokens,
        /** 端到端时延 ms (settle 语义, 原样重放). */
        Integer responseTimeMs,
        /** 失败尝试明细 (settle, G4 路由损耗留痕; 空/null = 无明细语义). */
        List<SettleRequest.AttemptDetail> attempts,
        /** 退款原因 (refund 型). */
        String refundReason,
        /** 已重放次数 (0 = 首次入队后尚未重放过; 每次重放失败 +1). */
        int retries) {

    public static final String TYPE_SETTLE = "settle";
    public static final String TYPE_REFUND = "refund";

    /** settle 型快照 (attempts 空表归一为 null, 与 settleFull 的「空不携带」语义一致). */
    public static BillingPendingRecord forSettle(String preConsumeId, String requestId,
                                                 Long ownerPartyId,
                                                 int actualPromptTokens, int actualCompletionTokens,
                                                 int cacheReadTokens, int cacheCreationTokens,
                                                 Long reasoningTokens, Long audioTokens,
                                                 int responseTimeMs,
                                                 List<SettleRequest.AttemptDetail> attempts) {
        boolean hasAttempts = attempts != null && !attempts.isEmpty();
        return new BillingPendingRecord(TYPE_SETTLE, preConsumeId, requestId, ownerPartyId,
                actualPromptTokens, actualCompletionTokens, cacheReadTokens, cacheCreationTokens,
                reasoningTokens, audioTokens, responseTimeMs,
                hasAttempts ? attempts : null, null, 0);
    }

    /** refund 型快照. */
    public static BillingPendingRecord forRefund(String preConsumeId, String requestId,
                                                 String reason) {
        return new BillingPendingRecord(TYPE_REFUND, preConsumeId, requestId, null,
                null, null, null, null, null, null, null, null, reason, 0);
    }

    public boolean isRefund() {
        return TYPE_REFUND.equals(type);
    }

    /** 重放失败重排: 仅递增重试计数, 结算参数原样保留 (重放同参数, 幂等锚不变). */
    public BillingPendingRecord withRetries(int nextRetries) {
        return new BillingPendingRecord(type, preConsumeId, requestId, ownerPartyId,
                actualPromptTokens, actualCompletionTokens, cacheReadTokens, cacheCreationTokens,
                reasoningTokens, audioTokens, responseTimeMs, attempts, refundReason, nextRetries);
    }

    /** 还原 settle 请求体 (与入队前的原始 SettleRequest 逐字段一致; attempts 空不携带). */
    public SettleRequest toSettleRequest() {
        SettleRequest.SettleRequestBuilder builder = SettleRequest.builder()
                .preConsumeId(preConsumeId)
                .actualPromptTokens(actualPromptTokens == null ? 0 : actualPromptTokens)
                .actualCompletionTokens(actualCompletionTokens == null ? 0 : actualCompletionTokens)
                .cacheReadTokens(cacheReadTokens == null ? 0 : cacheReadTokens)
                .cacheCreationTokens(cacheCreationTokens == null ? 0 : cacheCreationTokens)
                .reasoningTokens(reasoningTokens)
                .audioTokens(audioTokens)
                .success(true)
                .requestId(requestId)
                .ownerPartyId(ownerPartyId)
                .responseTimeMs(responseTimeMs == null ? 0 : responseTimeMs);
        if (attempts != null && !attempts.isEmpty()) {
            builder.attempts(attempts);
        }
        return builder.build();
    }
}
