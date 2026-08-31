package fun.commons.tokengateway.spi.model;

import java.util.Map;

/**
 * 审计事件 (AUDIT 面入参, M0 冻结契约).
 *
 * <p>安全/管理事件: 租户/密钥生命周期, 审核拦截, 配置变更等.
 * type 约定: dot 分层 (如 moderation.block / key.created / config.changed).
 */
public record AuditEvent(
        String type,
        String traceId,
        String tenantId,
        String userId,
        String actor,
        Map<String, Object> detail,
        long ts) {
}
