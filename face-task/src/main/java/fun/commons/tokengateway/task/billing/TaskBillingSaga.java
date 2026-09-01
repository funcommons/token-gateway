package fun.commons.tokengateway.task.billing;

import fun.commons.tokengateway.contract.PreConsumeRequest;
import fun.commons.tokengateway.contract.PreConsumeVO;
import fun.commons.tokengateway.contract.RefundRequest;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.idempotency.IdempotencyStore;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 任务计费 saga (《05》§7: 全额预扣 → 终态退款, 无 usage 结算步).
 *
 * <p>与 LLM 面差异: 任务按模型全额定价 (先路由定价再预扣, 模型不同价不同),
 * 无 token 估算 — estimatedPromptTokens/estimatedCompletionTokens 传 0,
 * 金额由 billing 面按 model 定价表裁决 (控制层决议 2026-09-01).
 *
 * <p>退款幂等: pre_consume_id 为幂等键 (billing 面自身幂等), 网关侧再以
 * IdempotencyStore 去重防并发重复退款 (webhook 重投/对账补偿/超时钟三源触发).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskBillingSaga {

    /** 退款去重占位 TTL (覆盖最长对账周期). */
    private static final Duration REFUND_GUARD_TTL = Duration.ofDays(7);

    private final HttpBillingApi billingApi;
    private final IdempotencyStore idempotencyStore;

    /**
     * 全额预扣.
     *
     * @return preConsumeId; 余额不足 → 402 + 10617 (不产生任务); 其余失败 → 502 + 10004
     */
    public Mono<String> preConsumeFull(TokenValidateVO token, String channelId, String ownerType,
                                       String model, String requestId) {
        return billingApi.preConsume(PreConsumeRequest.builder()
                        .tenantId(token.getTenantId())
                        .userId(token.getUserId())
                        .tokenId(token.getTokenId())
                        .channelId(channelId)
                        .model(model)
                        .ownerType(ownerType)
                        .estimatedPromptTokens(0)
                        .estimatedCompletionTokens(0)
                        .requestId(requestId)
                        .build())
                .map(resp -> {
                    if (resp == null || !resp.isSuccess() || resp.getData() == null
                            || !resp.getData().isSuccess()) {
                        String reason = failReason(resp);
                        if (resp != null && resp.getCode() == ApiCode.INSUFFICIENT_BALANCE.getCode()) {
                            throw new RelayException(402, ApiCode.INSUFFICIENT_BALANCE.getCode(),
                                    "余额不足" + (reason == null ? "" : ": " + reason));
                        }
                        throw new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                                "billing preConsume failed" + (reason == null ? "" : ": " + reason));
                    }
                    return resp.getData().getPreConsumeId();
                });
    }

    /**
     * 终态全额退款 (幂等: 三源触发只退一次).
     * fire-and-forget 语义: 退款 RPC 失败仅记日志, 由对账兜底任务补偿 (《05》§7).
     */
    public Mono<Void> refundOnce(String preConsumeId, String reason, String requestId) {
        if (preConsumeId == null) {
            return Mono.empty();
        }
        return idempotencyStore.tryAcquire("tgw:task:refund:" + preConsumeId, REFUND_GUARD_TTL)
                .flatMap(first -> {
                    if (!first) {
                        log.info("[TaskBilling] 退款已执行过, 幂等跳过: preConsumeId={}", preConsumeId);
                        return Mono.empty();
                    }
                    return billingApi.refund(RefundRequest.builder()
                                    .preConsumeId(preConsumeId)
                                    .reason(reason)
                                    .requestId(requestId)
                                    .build())
                            .doOnError(e -> log.error("[TaskBilling] refund RPC 失败: preConsumeId={}, err={}",
                                    preConsumeId, e.getMessage()))
                            .onErrorResume(e -> Mono.empty())
                            .then();
                });
    }

    private static String failReason(ApiResponse<PreConsumeVO> resp) {
        if (resp == null) {
            return null;
        }
        if (resp.getData() != null && resp.getData().getFailReason() != null) {
            return resp.getData().getFailReason();
        }
        return resp.getMessage();
    }
}
