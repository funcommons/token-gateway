package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 输出审查请求 (对齐主应用 moderation 模块 AuditRequest).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationAuditRequest {
    private String content;
    /** 上游渠道 baseUrl (用于路由 /v1/moderations); null 时走本地 */
    private String baseUrl;
    /** 上游渠道 apiKey; null 时跳过上游 */
    private String apiKey;
    @Builder.Default
    private String protocol = "openai";
    private String tenantId;
    private String requestId;
}
