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

    /**
     * 细分留痕维 (issue #26 可选新增, 不参与计价; null = 无源数据, 旧消费方忽略未知字段;
     * 主应用 AccessLogEntity 落列由 token 侧同步). OpenAI completion_tokens_details.reasoning_tokens.
     */
    private Integer reasoningTokens;

    /** 细分留痕维 (issue #26): OpenAI prompt/completion 两侧 audio_tokens 求和. */
    private Integer audioTokens;

    /** 细分留痕维 (issue #26): Anthropic cache_creation_input_tokens 口径定版独立成维. */
    private Integer cacheCreationTokens;

    /**
     * usage 真相位 (issue #35, 与 settle 同源同值): 取值见
     * {@link SettleRequest#USAGE_SOURCE_UPSTREAM} / {@link SettleRequest#USAGE_SOURCE_ESTIMATED}
     * (能力面 schema {@code usage_call_log.usage_source CHECK IN ('UPSTREAM','ESTIMATED')});
     * null = 旧版本语义/错误路径无用量概念, 不造数.
     */
    private String usageSource;

    private BigDecimal creditConsumed;
    private String billingMode;
    private Integer latencyMs;
}
