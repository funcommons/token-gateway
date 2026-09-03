package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 渠道路由请求 (从 backend/contracts 复制, 字段对齐主应用 RPC 入参).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributeRequest {
    private String tenantId;
    private String userId;
    private String apiKeyId;
    private String groupId;
    private String model;
    /** 本次请求已失败的 channel ID (请求内轮换排除用, 首次分发为 null) */
    private List<String> excludeChannelIds;
}
