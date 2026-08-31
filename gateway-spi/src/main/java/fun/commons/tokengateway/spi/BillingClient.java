package fun.commons.tokengateway.spi;

import fun.commons.tokengateway.contract.PreConsumeRequest;
import fun.commons.tokengateway.contract.PreConsumeVO;
import fun.commons.tokengateway.contract.RefundRequest;
import fun.commons.tokengateway.contract.SettleRequest;
import fun.commons.tokengateway.contract.SettleVO;
import reactor.core.publisher.Mono;

/**
 * 计费面 (BILLING, 设计方案 §4.2) — saga 三件套.
 *
 * <p>saga 保证: 每个 trace 最终恰好吃掉 预扣−退款 = 实际消费.
 * settle / refund 必须按 preConsumeId 幂等 (重复请求返回首次结果, 不得重复扣退).
 * 余额不足 → 10617 语义; billing=passthrough/off 时管线不调用本面.
 */
public interface BillingClient extends CapabilityFacade {

    /** 预扣 (转发前); 余额不足返回 10617 语义. */
    Mono<PreConsumeVO> preConsume(PreConsumeRequest request);

    /** 结算 (上游成功, 按实际 usage); 幂等键 preConsumeId. */
    Mono<SettleVO> settle(SettleRequest request);

    /** 退款 (saga 补偿: 上游全失败/超时, 全额退预扣); 幂等键 preConsumeId. */
    Mono<Void> refund(RefundRequest request);

    @Override
    default Capability capability() {
        return Capability.BILLING;
    }
}
