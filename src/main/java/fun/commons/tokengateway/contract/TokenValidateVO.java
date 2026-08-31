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
}
