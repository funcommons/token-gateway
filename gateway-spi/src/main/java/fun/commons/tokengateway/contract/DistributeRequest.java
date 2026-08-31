package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
