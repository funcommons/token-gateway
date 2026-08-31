package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 渠道路由返回 (从 backend/contracts 复制, 字段对齐主应用 RPC 响应).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributeVO {
    private String channelId;
    private Integer type;
    private String baseUrl;
    private String apiKey;
    private Map<String, String> modelMapping;
    private Integer responseTime;

    /** 上游协议: openai/anthropic/gemini */
    private String protocol;

    /**
     * 渠道归属类型: PLATFORM (平台渠道) / TENANT (租户 BYOK).
     * <p>V087 起作为路由 + 计费判定真源.
     */
    @Builder.Default
    private OwnerType ownerType = OwnerType.PLATFORM;
}
