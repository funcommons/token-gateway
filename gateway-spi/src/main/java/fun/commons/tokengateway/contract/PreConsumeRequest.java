package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreConsumeRequest {
    private String userId;
    private String tokenId;
    private String channelId;
    private String model;
    private int estimatedPromptTokens;
    private int estimatedCompletionTokens;
    private String requestId;
    private String relayFormat;
    private String tenantId;
    /** 渠道归属类型: "PLATFORM" / "TENANT" (V087 计费判定真源, 缺失按 PLATFORM) */
    private String ownerType;

    /**
     * 接入方定价透传 (#12: 全额积分; null=走 token 估算, 行为不变).
     */
    private Integer amount;
}
