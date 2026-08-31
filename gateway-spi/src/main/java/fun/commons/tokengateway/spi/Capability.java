package fun.commons.tokengateway.spi;

/**
 * 能力面枚举 (M0 冻结契约, 设计方案 §4.1).
 *
 * <p>每个能力面对应 yml 一节配置 (url + auth + 开关 + 超时), 以及一个 SPI 接口
 * (七面共用 + 任务委托面可选).
 */
public enum Capability {

    /** 调用方凭证校验 → {@link TokenValidator}. */
    TOKEN_VALIDATE,

    /** 模型 → 渠道路由解析 (distribute / 候选解析) → {@link RouteResolver}. */
    ROUTE_RESOLVE,

    /** 内容审核扫描 → {@link ModerationScanner}. */
    MODERATION_SCAN,

    /** 计费三件套 (预扣/结算/退款) → {@link BillingClient}. */
    BILLING,

    /** 访问日志投递 (RPC 同步 / MQ 异步两类通道) → {@link AccessLogSink}. */
    ACCESS_LOG,

    /** 审计事件外发 (安全/管理事件, 含审核审计上报) → {@link AuditSink}. */
    AUDIT,

    /** 前台模型目录 → {@link ModelCatalog}. */
    MODEL_CATALOG,

    /** 任务创建委托 (后端自持任务状态时可选, 与 TASK_POLL 成对) → {@link TaskClient}. */
    TASK_CREATE,

    /** 任务轮询委托 (与 TASK_CREATE 成对) → {@link TaskClient}. */
    TASK_POLL
}
