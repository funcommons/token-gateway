package fun.commons.tokengateway.spi.config;

/**
 * 计费模式三值 (设计方案 §5.3) — 非布尔.
 */
public enum BillingMode {
    /** 网关 saga 计费 (预扣 → 结算 → 失败退款, 走 BILLING 面). */
    DIRECT,
    /** 上游/后端自计费 (THMP 闭环), 网关只透传 usage 记日志. */
    PASSTHROUGH,
    /** 不计费 (BYOK/内网直通). */
    OFF
}
