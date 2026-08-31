package fun.commons.tokengateway.spi.model;

import java.math.BigDecimal;

/**
 * 访问日志条目 (ACCESS_LOG 面入参, M0 冻结契约).
 *
 * <p>字段对齐后端接入开发手册 §4.5; MQ 通道消费端按 (traceId, ts) 幂等去重.
 */
public record AccessLogEntry(
        String traceId,
        String tenantId,
        String userId,
        String apiKeyId,
        String channelId,
        String model,
        String requestMethod,
        String requestPath,
        int statusCode,
        int promptTokens,
        int completionTokens,
        int cachedTokens,
        BigDecimal creditConsumed,
        String billingMode,
        int latencyMs,
        long ts) {
}
