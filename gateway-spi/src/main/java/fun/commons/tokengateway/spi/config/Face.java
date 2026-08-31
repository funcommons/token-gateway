package fun.commons.tokengateway.spi.config;

/**
 * 装配面 (token-gateway.face, 设计方案 §5.1) — 部署分组.
 */
public enum Face {
    /** 纯同步面 (无本地盘依赖, 弹性扩). */
    LLM,
    /** 任务面 (挂资源缓存盘, 独立扩缩). */
    TASK,
    /** 单组合跑 (小规模). */
    ALL
}
