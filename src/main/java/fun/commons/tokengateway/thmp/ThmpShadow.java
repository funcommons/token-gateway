package fun.commons.tokengateway.thmp;

import fun.commons.tokengateway.contract.DistributeVO;

/**
 * THMP 影子双跑埋点入口 (22 号 S2-W1).
 *
 * <p>实现必须 fire-and-forget: 绝不阻塞、绝不抛出、绝不影响旧 distribute 主链路.
 * gateway.thmp.enabled=false 时装配 {@link Noop}.
 */
public interface ThmpShadow {

    /**
     * 双跑: 旧渠道结果 + THMP resolve 并行比对, 结构化日志埋点.
     *
     * @param model    前台 chat code (旧 distribute 与 THMP model_code 同名 — 决议: 沿用旧 code)
     * @param tenantId 旧世界租户 ID (非数字时归 0 比对公共渠道, 见实现类 javadoc)
     * @param oldRoute 旧 distribute 返回 (真源, 只读)
     */
    void compare(String model, String tenantId, DistributeVO oldRoute);

    /**
     * 关闭态: 零开销直返.
     */
    class Noop implements ThmpShadow {

        @Override
        public void compare(String model, String tenantId, DistributeVO oldRoute) {
            // 影子双跑未开启 (gateway.thmp.enabled=false)
        }
    }
}
