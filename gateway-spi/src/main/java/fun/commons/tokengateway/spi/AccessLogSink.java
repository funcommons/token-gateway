package fun.commons.tokengateway.spi;

import fun.commons.tokengateway.spi.model.AccessLogEntry;
import reactor.core.publisher.Mono;

/**
 * 访问日志面 (ACCESS_LOG, 设计方案 §4.2).
 *
 * <p>transport = RPC (同步 HTTP 调日志服务) | MQ (Kafka/RocketMQ 异步投递);
 * 开关关闭时管线不调用. MQ 语义 at-least-once, 消费端按 (trace_id, ts) 幂等.
 * 失败不阻塞主链路 (quiet + 告警, 对齐既有降级口径).
 */
public interface AccessLogSink extends CapabilityFacade {

    Mono<Void> record(AccessLogEntry entry);

    @Override
    default Capability capability() {
        return Capability.ACCESS_LOG;
    }
}
