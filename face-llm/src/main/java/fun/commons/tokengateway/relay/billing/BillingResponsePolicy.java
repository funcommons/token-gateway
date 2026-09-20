package fun.commons.tokengateway.relay.billing;

import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;

/**
 * settle/refund 响应三分类 (issue #23): 确认成功 / 可重试基础设施失败 / 业务拒绝.
 *
 * <p>前提事实: {@code HttpBillingApi} 把 RPC 异常/超时/HTTP 5xx 统一 onErrorResume
 * 折叠为信封 code=10003 (SERVICE_TIMEOUT) 的 fail 响应, 编排层永远拿不到异常本体——
 * 因此「基础设施失败」在编排层的唯一可判信号即 10003 折叠码.
 *
 * <p>判定顺序约定: 调用方先按各端点语义判「确认成功」(settle 须 creditConsumed 非空,
 * refund code=0 即确认), 未确认再进本策略.
 */
public final class BillingResponsePolicy {

    private BillingResponsePolicy() {
    }

    /**
     * 可重试基础设施失败 (前置: 响应未被确认为成功): 无响应 / 10003 折叠码
     * (RPC 异常/超时/5xx) / code=0 但载荷不完整 (settle 无 creditConsumed).
     *
     * <p>code=0 载荷不完整视同可重试: 重放幂等由 preConsumeId 锚定, 多发一次
     * settle/refund 无副作用, 漏发才是资损.
     */
    public static boolean retryableInfra(ApiResponse<?> resp) {
        return resp == null || resp.isSuccess()
                || resp.getCode() == ApiCode.SERVICE_TIMEOUT.getCode();
    }

    /**
     * 业务拒绝 (前置: 未确认成功且非基础设施失败): 信封 code!=0 且非 10003 折叠码
     * ——同参数重试结果不变, 不入 pending, 只死信留痕一次.
     */
    public static boolean businessRejected(ApiResponse<?> resp) {
        return !retryableInfra(resp);
    }
}
