package fun.commons.tokengateway.spi.config;

/**
 * 访问日志投递通道 (设计方案 §5.3).
 */
public enum LogTransport {
    /** 同步 HTTP 调日志服务. */
    RPC,
    /** MQ 异步投递 (at-least-once, 消费端按 (trace_id, ts) 幂等). */
    MQ
}
