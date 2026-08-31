package fun.commons.tokengateway.spi;

import fun.commons.tokengateway.spi.model.AuditEvent;
import reactor.core.publisher.Mono;

/**
 * 审计面 (AUDIT, 设计方案 §4.2).
 *
 * <p>安全/管理事件外发 (租户/密钥生命周期, 审核拦截, 配置变更; 含审核审计上报).
 * 失败不阻塞主链路 (quiet + 告警); 实现方应 append-only 落库, 禁物理删除.
 */
public interface AuditSink extends CapabilityFacade {

    Mono<Void> record(AuditEvent event);

    @Override
    default Capability capability() {
        return Capability.AUDIT;
    }
}
