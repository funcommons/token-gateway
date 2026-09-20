package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Token 校验返回 (从 backend/contracts 复制, 字段对齐主应用 RPC 响应).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenValidateVO {
    private boolean valid;
    private String tokenId;
    private String userId;
    private String tenantId;
    private String groupId;
    private String userGroup;
    private boolean unlimitedQuota;
    @Builder.Default
    private BigDecimal remainQuota = BigDecimal.ZERO;
    private boolean modelAllowed;
    private boolean ipAllowed;

    /**
     * 子账号 ID (可选, issue #27): 能力面按自有账号体系回填, 网关仅透传消费
     * (经 PreparedRequest.token 可取), 不做存在性强依赖; 缺省为 null.
     */
    private String subAccountId;
}
