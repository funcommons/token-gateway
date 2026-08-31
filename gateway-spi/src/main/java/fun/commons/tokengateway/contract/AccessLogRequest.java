package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 访问日志 DTO (从 backend/audit AccessLogEntity 复制, 字段对齐主应用 RPC).
 *
 * <p>Gateway-webflux 在 settle/refund 后 fire-and-forget 上报访问日志.
 * 字段需与 {@code fun.commons.mmagix.audit.AccessLogEntity} 严格对齐
 * (Jackson 按字段名序列化).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessLogRequest {
    private String traceId;
    private Long tenantId;
    private Long userId;
    private Long apiKeyId;
    private Long channelId;
    private String modelCode;
    private String requestMethod;
    private String requestPath;
    private Integer statusCode;
    private Integer upstreamStatus;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer cachedTokens;
    private BigDecimal creditConsumed;
    private String billingMode;
    private Integer latencyMs;
}
